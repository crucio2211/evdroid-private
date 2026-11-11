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
	@Volatile
	private var selectedEngineLanguage: Int? = null
	// Match Apple's per-language native engine cache. Each OpenEVV handle
	// owns its dictionary set, so retain it while another language is active.
	private val engines = java.util.concurrent.ConcurrentHashMap<Int, EvvEngine>()
	private var settings: Settings? = null
	private val engineExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
		Thread(task, "evvdroid-engine-load").apply { isDaemon = true }
	}
	private val engineLoadLock = Any()
	// A pending load is tracked per language, so rapid language requests do
	// not overwrite one another or queue duplicate ECI initializations.
	private val pendingEngines = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.Future<EvvEngine?>>()

	/** Written on one binder thread and read on another: a stop the pump
	 *  never sees is audio committed past it, and that is the stale sliver
	 *  at the head of the next utterance. */
	@Volatile
	private var stopped = false

	private val appliedRevisions = java.util.concurrent.ConcurrentHashMap<Int, Int>()

	// A snapshot belongs to the native engine language, not to whichever
	// language happened to be selected most recently.
	private val dictionarySnapshots = HashMap<Int, DictionarySnapshot>()
	private val dictionarySnapshotLock = Any()

	private var loadedRegex: RegexDictionaries.Loaded? = null
	private var regexEnabled = false

	override fun onCreate() {
		settings = Settings(this)
		super.onCreate()
		// The emoji table builds on first touch, which would otherwise land
		// on the first utterance. Warm it away from the speech thread.
		Thread({ EmojiNames.STARTERS.size }, "emoji-warmup").start()

		// Screen readers often reconnect and speak immediately after the TTS
		// service is created. Start the likely engine now, rather than waiting
		// for onLoadLanguage or the first onSynthesizeText call to begin loading
		// Main, Root and Abbreviations. This work stays on engineExecutor, so
		// binding and settings callbacks remain responsive.
		val selected = settings
		val systemLocale = Locale.getDefault()
		val requested = if (selected?.forceLanguage == true &&
			EvvEngine.available.contains(selected.forcedLanguage)
		) {
			selected.forcedLanguage
		} else {
			matchLanguage(systemLocale.language, systemLocale.country)?.first
				?: EvvEngine.available.firstOrNull()
		}
		if (requested != null) {
			selectedEngineLanguage = requested
			Log.i(TAG, "prewarming the likely screen-reader language 0x%08x".format(requested))
			requestEngine(requested)
		}
	}


	override fun onDestroy() {
		super.onDestroy()
		// Engine creation and native dictionary loading can take longer than
		// two seconds for a large Root volume. Closing the ECI handle after a
		// timed-out wait races the still-running loader and can leave the
		// native worker in a damaged state. Queue destruction behind every
		// pending engine task instead; the handle is closed only after loading
		// or replacement has returned.
		synchronized(engineLoadLock) {
			try {
				engineExecutor.execute {
					for ((language, cached) in engines.entries) {
						try {
							cached.close()
						} catch (failed: Exception) {
							Log.e(TAG, "could not close the ECI engine for 0x%08x".format(language), failed)
						}
					}
					engines.clear()
					pendingEngines.clear()
					engine = null
					appliedRevisions.clear()
					retireDictionarySnapshots()
				}
			} catch (rejected: java.util.concurrent.RejectedExecutionException) {
				Log.w(TAG, "engine cleanup was already queued", rejected)
			}
			engineExecutor.shutdown()
		}
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
		selectedEngineLanguage = found.first
		requestEngine(found.first)
		return found.second
	}

	override fun onGetLanguage(): Array<String> {
		val language = selectedEngineLanguage ?: engine?.language ?: EvvEngine.available.firstOrNull()
			?: return arrayOf("eng", "USA", "")
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
		selectedEngineLanguage = found.first
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
		engines[language]?.let { ready ->
			val completed = java.util.concurrent.FutureTask<EvvEngine?>(java.util.concurrent.Callable { ready })
			completed.run()
			return@synchronized completed
		}
		pendingEngines[language]?.takeIf { !it.isDone }?.let {
			return@synchronized it
		}
		engineExecutor.submit<EvvEngine?> { openEngine(language) }.also {
			pendingEngines[language] = it
		}
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
		val held = engines[language]
		if (held != null) {
			return held
		}
		val made = EvvEngine.open(language)
		if (made != null) {
			engines[language] = made
			applySettings(made)
		}
		return made
	}

	private fun applySettings(target: EvvEngine) {
		val s = settings ?: return
		target.setSampleRate(s.sampleRateHz)
		val pauseModeReady = target.setPauseMode(s.pauses)
		target.setAbbreviations(s.abbreviations)
		val dictionariesReady = loadDictionaries(target, s)
		loadRegexDictionary(s)
		// A voice is written, so the engine can refuse it while it is speaking.
		// A failed pause mode, dictionary publication or voice update is retried.
		val voiceReady = target.applyVoice(s.voice, s.shape(s.voice))
		// A bad dictionary file must not make every subsequent utterance repeat
		// a full Root-volume hash and native load attempt. The loader already
		// rebuilds and retries a rejected cache once; another attempt is made
		// when the user changes settings or the selected dictionary.
		if (voiceReady && pauseModeReady) {
			appliedRevisions[target.language] = s.revision
		}
		if (!dictionariesReady) {
			Log.w(TAG, "dictionary setup failed for settings revision ${s.revision}; speech will continue without retrying on every utterance")
		}
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

	private fun retireDictionarySnapshots() = synchronized(dictionarySnapshotLock) {
		dictionarySnapshots.values.forEach { it.retire() }
		dictionarySnapshots.clear()
	}

	private fun acquireDictionarySnapshot(language: Int): DictionarySnapshot.Lease? =
		synchronized(dictionarySnapshotLock) {
			dictionarySnapshots[language]?.let { snapshot ->
				runCatching { snapshot.acquire() }.getOrNull()
			}
		}

	/** Builds one immutable snapshot for the active language, then publishes
	 *  Main, Root and Abbreviations together through a fresh native ECI handle.
	 *  Content hashes detect same-size/same-timestamp replacements. A failed
	 *  snapshot or native load leaves the previously active set untouched. */
	private fun loadDictionaries(target: EvvEngine, s: Settings): Boolean = synchronized(dictionarySnapshotLock) {
		val selected = s.dictionaryPaths().filterKeys { volume ->
			s.useDictionary(volume) && (volume != Eci.DICT_ABBREVIATION || s.abbreviations)
		}
		val sources = LinkedHashMap<Int, java.io.File>()
		for ((volume, path) in selected) {
			if (s.dictionaryLanguageResolved(volume, EvvEngine.available) != target.language) continue
			val file = java.io.File(path)
			if (file.isFile && file.canRead()) sources[volume] = file
		}
		val root = java.io.File(filesDir, "dictionary-snapshots")
		val started = android.os.SystemClock.elapsedRealtime()
		val previous = dictionarySnapshots[target.language]
		var snapshot = try {
			DictionarySnapshot.prepare(root, target.language, sources, previous)
		} catch (failed: Exception) {
			Log.e(TAG, "could not prepare the dictionary snapshot; keeping the active set", failed)
			return@synchronized false
		}
		if (snapshot === previous) {
			Log.i(TAG, "dictionary snapshot already warm for 0x%08x".format(target.language))
			return@synchronized true
		}
		var loaded = target.replaceDictionaries(snapshot.paths)
		if (!loaded) {
			// The cache manifest and file sizes are checked on warm starts to
			// avoid rereading a large Root volume. If native loading rejects that
			// cached image (for example after storage corruption), rebuild it
			// from the source dictionaries once before declaring failure.
			Log.w(TAG, "native dictionary load failed; rebuilding the cached snapshot")
			snapshot.retire()
			snapshot = try {
				DictionarySnapshot.prepare(root, target.language, sources, null, forceRebuild = true)
			} catch (failed: Exception) {
				Log.e(TAG, "dictionary snapshot rebuild failed; keeping the active set", failed)
				return@synchronized false
			}
			loaded = target.replaceDictionaries(snapshot.paths)
		}
		if (loaded) {
			dictionarySnapshots[target.language] = snapshot
			previous?.retire()
			val elapsed = android.os.SystemClock.elapsedRealtime() - started
			Log.i(TAG, "published dictionary snapshot ${snapshot.identity.take(16)} for 0x%08x in ${elapsed} ms".format(target.language))
			true
		} else {
			snapshot.retire()
			Log.e(TAG, "native engine refused the rebuilt dictionary snapshot; keeping the active set")
			false
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
		selectedEngineLanguage = language
		val target = ensureEngine(language)
		if (target == null) {
			callback.error(TextToSpeech.ERROR_SERVICE)
			return
		}
		// Track the engine actually speaking. Background prewarming or a
		// queued language load must never redirect onStop() to another handle.
		engine = target
		// Stopped first, NVDA-style: its driver cancels before every speak,
		// flushing whatever a previous utterance left behind when the reader
		// moved on without saying stop. eciStop on an idle engine is safe
		// here (upstream's fix for that wedge is in this tree).
		target.stop()
		if (s != null && s.revision != appliedRevisions[target.language]) applySettings(target)
		// Re-sent every time rather than once, because it is the one setting
		// with no way of telling whether something else has moved it.
		s?.let { target.setAbbreviations(it.abbreviations) }
		s?.let { target.setPauseMode(it.pauses) }
		// A forced rate or pitch is the voice's own Speed or Pitch row: the
		// voice is applied absolutely below, so 100 here leaves it alone and
		// whatever TalkBack asked for is ignored. Unforced, TalkBack's numbers
		// multiply the voice as before.
		val snapshotLease = acquireDictionarySnapshot(target.language)
		val nativeLease = target.acquireDictionaryLease()
		if (nativeLease == null) {
			snapshotLease?.close()
			callback.error(TextToSpeech.ERROR_SYNTHESIS)
			return
		}
		try {
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
		val prosody = Prosody.prefix(settings?.phrasePrediction ?: false)
		// No loudness tricks: an exclamation must read as one from its
		// tune alone. Snapshot.boost stays off (1) until intonation lands.
		val audio = s?.audioSnapshot() ?: AudioOutput.Snapshot()
		for (rawPiece in rawPieces) {
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
			val text = if (regexEnabled) RegexDictionaries.apply(loadedRegex, fixed) else fixed
			if (text.isEmpty()) continue
			// The native dictionary set already owns every entry in all three
			// volumes; speech is passed through without a Kotlin word-map pass.
			val piece = prosody + text
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
		} finally {
			nativeLease.close()
			snapshotLease?.close()
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
