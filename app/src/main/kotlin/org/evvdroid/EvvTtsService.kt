package org.evvdroid

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * The engine Android talks to.
 *
 * onSynthesizeText runs on the framework's own synthesis thread and is the
 * only place an utterance is driven; onStop arrives from another thread and
 * does nothing but set the engine's abort flag, which is what unblocks the
 * read below.
 */
class EvvTtsService : TextToSpeechService() {

	@Volatile
	private var engine: EvvEngine? = null
	private var settings: Settings? = null
	private val engineExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
		Thread(task, "evvdroid-engine-load").apply { isDaemon = true }
	}
	private val engineLoadLock = Any()
	private var pendingEngineLanguage: Int? = null
	private var pendingEngine: java.util.concurrent.Future<EvvEngine?>? = null

	/** Written on one binder thread and read on another: a stop the pump
	 *  never sees is audio committed past it, and that is the stale sliver
	 *  at the head of the next utterance. */
	@Volatile
	private var stopped = false

	private var appliedRevision = -1

	private var loadedDictionarySnapshot: String? = null
	private var loadedDictionaryEngine: EvvEngine? = null

	private var loadedRegex: RegexDictionaries.Loaded? = null
	private var regexEnabled = false

	override fun onCreate() {
		settings = Settings(this)
		super.onCreate()
		// The emoji table builds on first touch, which
		// would otherwise land on the first utterance. Warm it here, off
		// every thread that speaks.
		Thread({ EmojiNames.STARTERS.size }, "emoji-warmup").start()
	}


	override fun onDestroy() {
		super.onDestroy()
		engineExecutor.shutdown()
		try {
			engineExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
		} catch (interrupted: InterruptedException) {
			Thread.currentThread().interrupt()
		}
		engine?.close()
		engine = null
	}

	// ---- languages -------------------------------------------------------

	private fun matchLanguage(lang: String?, country: String?): Pair<Int, Int>? {
		if (lang.isNullOrEmpty()) return null
		val wantLang = normalise(lang)
		val wantCountry = country?.takeIf { it.isNotEmpty() }?.let { normaliseCountry(it) }
		var byLanguage: Int? = null
		for (candidate in EvvEngine.available) {
			val loc = Eci.localeOf(candidate) ?: continue
			if (!loc.first.equals(wantLang, ignoreCase = true)) continue
			if (wantCountry != null && loc.second.equals(wantCountry, ignoreCase = true)) {
				return candidate to TextToSpeech.LANG_COUNTRY_AVAILABLE
			}
			if (byLanguage == null) byLanguage = candidate
		}
		return byLanguage?.let { it to TextToSpeech.LANG_AVAILABLE }
	}

	private fun normalise(lang: String): String =
		runCatching { Locale(lang).isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() } ?: lang

	private fun normaliseCountry(country: String): String =
		runCatching { Locale("", country).isO3Country }.getOrNull()?.takeIf { it.isNotEmpty() } ?: country

	override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
		val found = matchLanguage(lang, country) ?: return TextToSpeech.LANG_NOT_SUPPORTED
		return found.second
	}

	override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
		val found = matchLanguage(lang, country) ?: return TextToSpeech.LANG_NOT_SUPPORTED
		requestEngine(found.first)
		return found.second
	}

	override fun onGetLanguage(): Array<String> {
		val language = engine?.language ?: EvvEngine.available.firstOrNull() ?: return arrayOf("eng", "USA", "")
		val loc = Eci.localeOf(language) ?: return arrayOf("eng", "USA", "")
		return arrayOf(loc.first, loc.second, loc.third)
	}

	// ---- voices ----------------------------------------------------------

	private fun voiceName(language: Int, preset: Int): String {
		val loc = Eci.localeOf(language) ?: return "evv-$preset"
		return "${loc.first}-${loc.second}-${Eci.PRESET_NAMES[preset]}"
	}

	override fun onGetVoices(): MutableList<Voice> {
		val out = ArrayList<Voice>()
		for (language in EvvEngine.available) {
			val loc = Eci.localeOf(language) ?: continue
			val locale = Locale(loc.first, loc.second)
			for (preset in Eci.PRESET_NAMES.indices) {
				out.add(
					Voice(
						voiceName(language, preset),
						locale,
						Voice.QUALITY_NORMAL,
						Voice.LATENCY_VERY_LOW,
						false,
						emptySet()
					)
				)
			}
		}
		return out
	}

	private fun findVoice(name: String?): Pair<Int, Int>? {
		if (name.isNullOrEmpty()) return null
		for (language in EvvEngine.available) {
			for (preset in Eci.PRESET_NAMES.indices) {
				if (voiceName(language, preset) == name) return language to preset
			}
		}
		return null
	}

	override fun onIsValidVoiceName(name: String?): Int =
		if (findVoice(name) != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR

	override fun onLoadVoice(name: String?): Int {
		val found = findVoice(name) ?: return TextToSpeech.ERROR
		requestEngine(found.first)
		return TextToSpeech.SUCCESS
	}

	override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? {
		val found = matchLanguage(lang, country) ?: return null
		return voiceName(found.first, settings?.voice ?: 0)
	}

	// ---- speaking --------------------------------------------------------

	/** The language to speak in: the forced one when set and linked into the
	 *  engine, else the caller's. A forced language from a build with fewer
	 *  languages falls back instead of failing the utterance. */
	private fun resolveLanguage(s: Settings?, fallback: Int): Int {
		if (s?.forceLanguage == true) {
			val forced = s.forcedLanguage
			if (EvvEngine.available.contains(forced)) return forced
			Log.w(TAG, "forced language ${"0x%08x".format(forced)} is not linked; using the requested one")
		}
		return fallback
	}

	/** Language selection starts engine and dictionary setup on a worker. The
	 *  synthesis callback joins that same task only if speech arrives first. */
	private fun requestEngine(language: Int): java.util.concurrent.Future<EvvEngine?> = synchronized(engineLoadLock) {
		engine?.takeIf { it.language == language }?.let { ready ->
			val completed = java.util.concurrent.FutureTask<EvvEngine?>(java.util.concurrent.Callable { ready })
			completed.run()
			return@synchronized completed
		}
		pendingEngine?.takeIf { pendingEngineLanguage == language && !it.isDone }?.let {
			return@synchronized it
		}
		pendingEngineLanguage = language
		engineExecutor.submit<EvvEngine?> { openEngine(language) }.also { pendingEngine = it }
	}

	private fun ensureEngine(language: Int): EvvEngine? {
		return try {
			requestEngine(language).get()
		} catch (interrupted: InterruptedException) {
			Thread.currentThread().interrupt()
			null
		} catch (failed: java.util.concurrent.ExecutionException) {
			Log.e(TAG, "engine setup failed for 0x%08x".format(language), failed.cause ?: failed)
			null
		}
	}

	/** Called only by [engineExecutor], so changing language cannot race another
	 *  engine construction. Dictionary files are attached before the task ends. */
	private fun openEngine(language: Int): EvvEngine? {
		val held = engine
		if (held != null && held.language == language) return held
		held?.close()
		engine = null
		val made = EvvEngine.open(language)
		engine = made
		// A new engine owns a fresh native dictionary set.
		if (made != null) applySettings(made)
		return made
	}

	private fun applySettings(target: EvvEngine) {
		val s = settings ?: return
		target.setSampleRate(s.sampleRateHz)
		target.setPauseMode(s.pauses)
		target.setAbbreviations(s.abbreviations)
		val dictionariesReady = loadDictionaries(target, s)
		loadRegexDictionary(s)
		// A voice is written, so the engine can refuse it while it is speaking.
		// One that did not land is left unrecorded and asked for again at the
		// next utterance rather than believed.
		if (target.applyVoice(s.voice, s.shape(s.voice)) && dictionariesReady) appliedRevision = s.revision
	}

	/** The regex dictionary is recompiled when the settings change, which is
	 *  the only time the file it reads can have changed: it is a copy of
	 *  our own, written by the settings screen. */
	private fun loadRegexDictionary(s: Settings) {
		regexEnabled = s.regexDictionaryEnabled
		val path = if (regexEnabled) s.regexDictionaryPath() else null
		val file = path?.let { java.io.File(it) }
		loadedRegex = if (file != null && file.exists()) {
			RegexDictionaries.load(file).also {
				Log.i(TAG, "regex dictionary: ${it?.entries?.size ?: 0} patterns from ${file.name}")
			}
		} else {
			null
		}
	}

	/** Activate a completed immutable snapshot once for this engine instance.
	 * Dictionary lookup stays in the native ECI table; speech never enters a
	 * Kotlin per-token dictionary path. */
	private fun loadDictionaries(target: EvvEngine, s: Settings): Boolean {
		var lease: DictionaryStore.Lease? = null
		try {
			lease = DictionaryStore.acquire(this, s, target.language)
			if (target === loadedDictionaryEngine && lease.id == loadedDictionarySnapshot) return true
			if (!target.replaceDictionaries(lease.volumes)) {
				Log.e(TAG, "native ECI refused dictionary snapshot " + lease.id)
				return false
			}
			loadedDictionaryEngine = target
			loadedDictionarySnapshot = lease.id
			Log.i(TAG, "activated dictionary snapshot " + lease.id + " with " + lease.volumes.size + " volumes")
			return true
		} catch (failed: Exception) {
			Log.e(TAG, "could not prepare external dictionary snapshot", failed)
			return false
		} finally {
			lease?.close()
		}
	}


	override fun onStop() {
		stopped = true
		engine?.stop()
	}

	override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
		if (request == null || callback == null) return
		stopped = false
		val s = settings
		val found = matchLanguage(request.language, request.country)
		if (found == null) {
			callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET)
			return
		}
		// The voice is whichever the settings screen says, not whichever the
		// caller named. A caller holds on to the Voice it was given, so a
		// screen reader that connected while Reed was chosen goes on asking
		// for Reed however many times the setting is changed underneath it.
		// The name still decides the language, since that is what it is for --
		// unless a language is forced, which overrules every caller.
		val wanted = findVoice(request.voiceName)
		val language = resolveLanguage(s, wanted?.first ?: found.first)
		val target = ensureEngine(language)
		if (target == null) {
			callback.error(TextToSpeech.ERROR_SERVICE)
			return
		}
		// Stopped first, NVDA-style: its driver cancels before every speak,
		// flushing whatever a previous utterance left behind when the reader
		// moved on without saying stop. eciStop on an idle engine is safe
		// here (upstream's fix for that wedge is in this tree).
		target.stop()
		if (s != null && s.revision != appliedRevision) applySettings(target)
		// Re-sent every time rather than once, because it is the one setting
		// with no way of telling whether something else has moved it.
		s?.let { target.setAbbreviations(it.abbreviations) }
		s?.let { target.setPauseMode(it.pauses) }
		// A forced rate or pitch is the voice's own Speed or Pitch row: the
		// voice is applied absolutely below, so 100 here leaves it alone and
		// whatever TalkBack asked for is ignored. Unforced, TalkBack's numbers
		// multiply the voice as before.
		target.setRatePercent(if (s != null && s.forceRate) 100 else request.speechRate)
		target.setPitchPercent(if (s != null && s.forcePitch) 100 else request.pitch)
		// One pipeline for TalkBack and the sample button alike; the shape of
		// it is documented on TextPipeline.
		val raw = request.charSequenceText?.toString().orEmpty()
		val numberMode: Int? = if (s != null && s.numberProcessing) s.numberMode else null
		val punctLevel: Int? = if (s != null && s.punctProcessing) s.punctLevel else null
		// Split before any rewriting, NVDA-style: each raw piece is
		// processed and spoken on its own, so the first audio lands after
		// the first piece's frontend rather than the whole block's. A
		// block-sized regex and dictionary scan plus one engine frontend
		// over all of it is what kept big text waiting.
		val rawPieces = TextPieces.split(raw)
		// The framework is not told an utterance has begun until there are
		// samples for it. Saying so up front and then being silenced before the
		// engine has made anything hands the reader a whole start and finish
		// with no speech in it, and TalkBack reads that as its queue running
		// dry: it answers "Completed speech while already completed", stops the
		// queue, and the item the finger is actually on dies with it. That is
		// what made dragging across a launcher go quiet. RHVoice opens the same
		// way, from inside its player rather than ahead of it.
		val opening = Opening(callback, target.sampleRateHz)
		opening.open()
		val pace = Pace(target.sampleRateHz * BYTES_PER_SAMPLE)
		// Piece by piece, so that asking for silence waits out a piece rather
		// than the whole message: the engine cannot abandon what it is saying.
		// Pauses are shortened after the split rather than before it: the
		// annotation sits in front of the full stop, and a piece boundary is
		// found by looking at the end of a word.
		val pauses = settings?.pauses ?: Pauses.ALL
		val prosody = Prosody.prefix(settings?.phrasePrediction ?: false)
		// No loudness tricks: an exclamation must read as one from its
		// tune alone. Snapshot.boost stays off (1) until intonation lands.
		val audio = s?.audioSnapshot() ?: AudioOutput.Snapshot()
		for ((ri, rawPiece) in rawPieces.withIndex()) {
			if (stopped) break
			val fixed = TextPipeline.process(
				rawPiece,
				emoji = s?.speakEmoji != false,
				emojiCompress = s?.emojiCompress ?: EmojiProcessing.DEFAULT_COMPRESS,
				numberMode = numberMode,
				camelCase = s?.splitCamelCase != false,
				stripBackquotes = s?.backquoteTags != true,
				punctLevel = punctLevel
			)
			// The user's own rewrites go last, closest to the engine, so what is
			// written in the regex dictionary is what gets said.
			val afterRegex = if (regexEnabled) RegexDictionaries.apply(loadedRegex, fixed) else fixed
			// Native snapshot volumes are already indexed in ECI; send text directly.
			val text = afterRegex
			if (text.isEmpty()) continue
			val pieces = TextPieces.split(text)
			for ((at, part) in pieces.withIndex()) {
				if (stopped) break
				val piece = prosody + Pauses.apply(
					part, pauses,
					at == pieces.lastIndex && ri == rawPieces.lastIndex
				)
		if (!target.speak(piece)) {
			Log.e(TAG, "the engine refused ${piece.length} characters")
			callback.error(TextToSpeech.ERROR_SYNTHESIS)
			return
		}
		if (!pump(target, callback, pace, opening, audio)) {
					remember(pace)
					return
				}
			}
		}
		remember(pace)
		if (stopped) {
			// Over, not done: finishing the callback flushes the tail it
			// is still holding, and that tail sounds at the head of
			// whatever comes next. A stopped utterance is left unfinished;
			// TalkBack asked for silence, not for completion.
		} else if (opening.began) callback.done()
		else {
			callback.start(target.sampleRateHz, AudioFormat.ENCODING_PCM_16BIT, 1)
			callback.done()
		}
	}

	/** Hands one piece's samples on as they arrive, and answers whether the
	 *  caller may go on to the next. The output stage is one chain for the
	 *  piece, so its filters run continuously across its chunks. */
	private fun pump(target: EvvEngine, callback: SynthesisCallback, pace: Pace, opening: Opening, audio: AudioOutput.Snapshot): Boolean {
		val size = callback.maxBufferSize.coerceIn(MIN_CHUNK, MAX_CHUNK)
		val buffer = ByteArray(size)
		val chain = AudioOutput.Chain(audio)
		while (!stopped) {
			val n = target.read(buffer)
			if (n <= 0) break
			// A chunk taken before the stop landed dies here, unplayed:
			// handing it over gives the framework audio for an utterance
			// already over, and it sounds at the head of the next one.
			if (stopped) {
				target.stop()
				return false
			}
			chain.process(buffer, n)
			if (!opening.open()) {
				target.stop()
				return false
			}
			if (callback.audioAvailable(buffer, 0, n) != TextToSpeech.SUCCESS) {
				// The framework has torn the callback down already, so done()
				// is neither wanted nor listened to.
				target.stop()
				return false
			}
			pace.handed(n)
		}
		return true
	}

	private fun remember(pace: Pace) {
		if (pace.handedMs() > mostAudioHandedMs) mostAudioHandedMs = pace.handedMs()
	}

	/** Tells the framework an utterance has begun, once, and not before there
	 *  is something to hear. [began] answers whether it was ever told, which is
	 *  what decides whether it is owed a finish. */
	private class Opening(private val callback: SynthesisCallback, private val rateHz: Int) {
		var began = false
			private set

		fun open(): Boolean {
			if (began) return true
			began = callback.start(rateHz, AudioFormat.ENCODING_PCM_16BIT, 1) == TextToSpeech.SUCCESS
			return began
		}
	}

	/** How far the audio handed over has run ahead of the time it takes to
	 *  play it. */
	private class Pace(private val bytesPerSecond: Int) {
		private var bytes = 0L

		fun handed(more: Int) {
			bytes += more
		}

		fun handedMs(): Long = if (bytesPerSecond <= 0) 0 else bytes * 1000L / bytesPerSecond

	}

	internal companion object {
		/** The most audio, in milliseconds, that any one utterance has handed
		 *  over since this was last cleared. Nothing in the app reads it. It is
		 *  what lets a test say the engine is not running ahead of the speech,
		 *  which is invisible from outside: an engine that queues six items in
		 *  a tenth of a second and one that paces itself look the same to the
		 *  framework and sound completely different to a person. */
		@Volatile
		var mostAudioHandedMs: Long = 0

		const val TAG = "evvdroid"
		const val MIN_CHUNK = 1024
		/** Small commits for a screen reader: what is handed over plays,
		 *  so a stop waits out at most this much, and speech starts in
		 *  slivers rather than after whole mouthfuls. */
		const val MAX_CHUNK = 4096
		const val BYTES_PER_SAMPLE = 2
	}
}
