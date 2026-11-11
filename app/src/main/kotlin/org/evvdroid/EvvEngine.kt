package org.evvdroid

import android.util.Log

/**
 * One engine instance and the settings in force on it.
 *
 * Everything but [stop] is called from the synthesis thread. [stop] comes from
 * whichever thread asked, which the native side is written for.
 */
class EvvEngine private constructor(private var handle: Long, val language: Int) {

	private val guard = Any()
	/** Dictionary calls may wait for a running utterance to finish. Keep that
	 * wait off [guard], which stop() needs in order to abort the utterance. */
	private val dictionaryGuard = Any()

	@Volatile
	private var closed = false

	var sampleRateHz: Int = Eci.sampleRateHz(1)
		private set

	var pauseMode: Int = Eci.PAUSES_DEFAULT
		private set

	/** What the voice was set to before Android's rate and pitch were scaled
	 *  onto it, so that a rate of 100% is the voice as it stands rather than
	 *  whatever the last utterance left behind. */
	private var baseSpeed = -1
	private var basePitch = -1

	/** What the rate and pitch last asked for come to in the engine's own
	 *  numbers, sent in front of every utterance. Below nought until a voice
	 *  has been applied, when there is no base to scale them onto. */
	private var wantSpeed = -1
	private var wantPitch = -1

	companion object {
		private const val TAG = "evvdroid"

		val available: IntArray by lazy {
			if (!EvvNative.loaded) IntArray(0) else runCatching { EvvNative.languages() }
				.onFailure { Log.e(TAG, "cannot ask for languages", it) }
				.getOrDefault(IntArray(0))
		}

		val version: String by lazy {
			if (!EvvNative.loaded) "" else runCatching { EvvNative.version() }.getOrDefault("")
		}

		/** Makes an instance speaking [language], which is one of the words
		 *  eciGetAvailableLanguages answered with and nothing else. */
		fun open(language: Int): EvvEngine? {
			if (!EvvNative.loaded) {
				Log.e(TAG, "the native library did not load", EvvNative.loadError)
				return null
			}
			val handle = EvvNative.create(language)
			if (handle == 0L) {
				Log.e(TAG, "no engine instance for language 0x%08x".format(language))
				return null
			}
		return EvvEngine(handle, language).apply { configure() }
		}
	}

	private fun configure() {
		EvvNative.setParam(handle, Eci.PARAM_LANGUAGE_DIALECT, language)
		EvvNative.setParam(handle, Eci.PARAM_SYNTH_MODE, 0)
		EvvNative.setParam(handle, Eci.PARAM_REAL_WORLD_UNITS, 0)
		setAnnotations(true)
		setPauseMode(Eci.PAUSES_DEFAULT)
		setSampleRate(Eci.sampleRateHz(EvvNative.getParam(handle, Eci.PARAM_SAMPLE_RATE)))
	}

	fun setPauseMode(mode: Int) = synchronized(guard) {
		if (closed) return
		val clamped = mode.coerceIn(Eci.PAUSES_NONE, Eci.PAUSES_ALWAYS)
		EvvNative.setParam(handle, Eci.PARAM_PAUSES, clamped)
		pauseMode = clamped
	}

	fun setSampleRate(hz: Int) = synchronized(guard) {
		if (closed) return
		val index = Eci.sampleRateIndex(hz)
		EvvNative.setParam(handle, Eci.PARAM_SAMPLE_RATE, index)
		val landed = Eci.sampleRateIndex(Eci.sampleRateHz(EvvNative.getParam(handle, Eci.PARAM_SAMPLE_RATE)))
		if (landed != index) {
			Log.e(TAG, "the engine refused $hz Hz, staying at 11025")
			EvvNative.setParam(handle, Eci.PARAM_SAMPLE_RATE, Eci.sampleRateIndex(11025))
			sampleRateHz = 11025
			return
		}
		sampleRateHz = Eci.sampleRateHz(index)
	}

	fun setAnnotations(on: Boolean) = synchronized(guard) {
		if (!closed) EvvNative.setParam(handle, Eci.PARAM_INPUT_TYPE, if (on) 1 else 0)
	}

	/** The abbreviation dictionary, which is what eciDictionary switches.
	 *  Nought turns it on: that is the engine's own sense of the setting and
	 *  not a mistake here. */
	fun setAbbreviations(on: Boolean) = synchronized(guard) {
		if (!closed) EvvNative.setParam(handle, Eci.PARAM_DICTIONARY, if (on) 0 else 1)
	}

	fun presetShape(index: Int): Map<Int, Int> = synchronized(guard) {
		if (closed) return emptyMap()
		val preset = (index + Eci.FIRST_PRESET).coerceIn(Eci.FIRST_PRESET, Eci.LAST_PRESET)
		val out = LinkedHashMap<Int, Int>()
		for (param in 0 until Eci.NUM_VOICE_PARAMS) {
			val value = EvvNative.getVoiceParam(handle, preset, param)
			if (value >= 0) out[param] = value
		}
		return out
	}

	fun applyVoice(index: Int, shape: Map<Int, Int> = emptyMap()): Boolean = synchronized(guard) {
		if (closed) return false
		val preset = (index + Eci.FIRST_PRESET).coerceIn(Eci.FIRST_PRESET, Eci.LAST_PRESET)
		if (EvvNative.copyVoice(handle, preset, Eci.SCRATCH_VOICE) == 0) return false
		if (EvvNative.setVoiceParam(
				handle, Eci.SCRATCH_VOICE, Eci.VOICE_SPEED,
				shape[Eci.VOICE_SPEED] ?: Eci.DEFAULT_SPEED
			) < 0
		) return false
		for ((param, value) in shape) {
			if (EvvNative.setVoiceParam(
					handle, Eci.SCRATCH_VOICE, param, Eci.clampVoice(param, value)
				) < 0
			) return false
		}
		if (EvvNative.copyVoice(handle, Eci.SCRATCH_VOICE, Eci.VOICE_CURRENT) == 0) return false
		baseSpeed = EvvNative.getVoiceParam(handle, Eci.VOICE_CURRENT, Eci.VOICE_SPEED)
		basePitch = EvvNative.getVoiceParam(handle, Eci.VOICE_CURRENT, Eci.VOICE_PITCH_BASELINE)
		wantSpeed = baseSpeed
		wantPitch = basePitch
		return true
	}

	fun setRatePercent(percent: Int) = synchronized(guard) {
		if (closed || baseSpeed < 0) return
		wantSpeed = SpeechRate.speedForPercent(baseSpeed, percent)
	}

	fun setPitchPercent(percent: Int) = synchronized(guard) {
		if (closed || basePitch < 0) return
		wantPitch = (basePitch.toLong() * percent / 100).toInt().coerceIn(0, Eci.PERCENT_MAX)
	}

	fun getVoiceParam(param: Int): Int = synchronized(guard) {
		if (closed) -1 else EvvNative.getVoiceParam(handle, Eci.VOICE_CURRENT, param)
	}

	/** Load saved binary dictionaries through ECI; read text dictionaries and
	 * teach their entries using the established per-word API. */
	fun loadDictionary(volume: Int, path: String): Int = synchronized(dictionaryGuard) {
		val current = synchronized(guard) { if (closed) 0L else handle }
		if (current == 0L) return@synchronized -1
		val file = java.io.File(path)
		if (!file.isFile || !file.canRead()) return@synchronized -1
		if (Dictionaries.isBinary(file)) {
			EvvNative.loadDictionary(current, volume, file.absolutePath)
		} else {
			Dictionaries.load(this, volume, file)
			0
		}
	}

	fun teachWord(volume: Int, key: String, say: String): Int = synchronized(dictionaryGuard) {
		val current = synchronized(guard) { if (closed) 0L else handle }
		if (current == 0L) -1 else EvvNative.teachWord(
			current, volume, EngineText.encode(language, key), EngineText.encode(language, say)
		)
	}

	fun lookUpWord(volume: Int, key: String): String? = synchronized(dictionaryGuard) {
		val current = synchronized(guard) { if (closed) 0L else handle }
		if (current == 0L) null else EvvNative.lookUpWord(current, volume, EngineText.encode(language, key))
	}

	fun forgetDictionaries() = synchronized(dictionaryGuard) {
		val current = synchronized(guard) { if (closed) 0L else handle }
		if (current != 0L) EvvNative.forgetDictionaries(current)
	}

	fun speak(text: String): Boolean = synchronized(guard) {
		if (closed) return false
		val ask = if (wantSpeed >= 0 && wantPitch >= 0) Prosody.voice(wantSpeed, wantPitch) else ""
		return EvvNative.speak(handle, EngineText.encode(language, ask + text))
	}

	fun read(into: ByteArray): Int {
		if (closed) return -1
		return EvvNative.read(handle, into)
	}

	fun stop() = synchronized(guard) {
		if (!closed) EvvNative.stop(handle)
	}

	fun close() {
		synchronized(dictionaryGuard) {
			val current = synchronized(guard) {
				if (closed) 0L else {
					closed = true
					handle.also { handle = 0 }
				}
			}
			if (current != 0L) EvvNative.destroy(current)
		}
	}
}
