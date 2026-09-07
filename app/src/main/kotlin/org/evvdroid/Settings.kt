package org.evvdroid

import android.content.Context
import android.content.SharedPreferences

/**
 * What the settings screen wrote, read back for the engine.
 *
 * Voice settings are kept per voice. Reed at pitch 55 is still at pitch 55
 * after a trip through Glen and back, and stays that way until Reed is reset.
 * Speed is the exception and is kept once for everybody: six of the eight
 * presets ship at engine speed 50 and Glen and Sandy ship at 70, so a speed
 * that followed the voice would change the pace every time the voice changed.
 *
 * Nothing is stored until someone chooses it. That is the whole point rather
 * than a detail: every preset carries its own head size, inflection and volume,
 * so a default of our own written over the top would make every voice wrong in
 * the same way, which is what it did once.
 *
 * A revision goes up whenever anything changes, so the service can tell whether
 * the instance it is holding is still configured the way the user left it
 * without comparing every value.
 */
class Settings(context: Context) {

	// The file androidx.preference used, kept under that name so a build with
	// the old screen and a build with this one read the same settings.
	private val prefs: SharedPreferences = context.applicationContext
		.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

	@Volatile
	var revision: Int = 0
		private set

	private val watcher = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }

	init {
		carryOverTheOldKeys()
		dropRetiredGenderKeys()
		prefs.registerOnSharedPreferenceChangeListener(watcher)
	}

	var voice: Int
		get() = prefs.getInt(KEY_VOICE, 0).coerceIn(0, Eci.PRESET_NAMES.lastIndex)
		set(value) = prefs.edit().putInt(KEY_VOICE, value).apply()

	var sampleRateHz: Int
		get() = Eci.coerceSampleRate(prefs.getInt(KEY_SAMPLE_RATE, DEFAULT_SAMPLE_RATE))
		set(value) = prefs.edit().putInt(KEY_SAMPLE_RATE, Eci.coerceSampleRate(value)).apply()

	/** The abbreviation dictionary. Off by default: it expands what it takes
	 *  for an abbreviation whether that was wanted or not. */
	var abbreviations: Boolean
		get() = prefs.getBoolean(KEY_ABBREVIATIONS, false)
		set(value) = prefs.edit().putBoolean(KEY_ABBREVIATIONS, value).apply()

	/** How much of the engine's own pausing to keep. One of the Pauses
	 *  constants, and not a voice's business. */
	var pauses: Int
		get() = prefs.getInt(KEY_PAUSES, Pauses.ALL).coerceIn(Pauses.KEEP, Pauses.ALL)
		set(value) = prefs.edit().putInt(KEY_PAUSES, value).apply()

	/** Whether the engine guesses at phrase boundaries and shapes the
	 *  intonation to match. Off: a screen reader's line is usually a fragment
	 *  and the guess is wrong. */
	var phrasePrediction: Boolean
		get() = prefs.getBoolean(KEY_PHRASE_PREDICTION, false)
		set(value) = prefs.edit().putBoolean(KEY_PHRASE_PREDICTION, value).apply()

	/** Whether text may carry the engine's backquote annotations, such as
	 *  `vs for speed or `p for a pause. Off: a backquote becomes a space
	 *  before the engine sees it, which keeps stray or hostile markup in
	 *  ordinary text from reaching the engine and crashing it. */
	var backquoteTags: Boolean
		get() = prefs.getBoolean(KEY_BACKQUOTE_TAGS, false)
		set(value) = prefs.edit().putBoolean(KEY_BACKQUOTE_TAGS, value).apply()

	/** One speed for every voice. */
	var speed: Int
		get() = Eci.clampVoice(Eci.VOICE_SPEED, prefs.getInt(KEY_SPEED, Eci.DEFAULT_SPEED))
		set(value) = prefs.edit().putInt(KEY_SPEED, Eci.clampVoice(Eci.VOICE_SPEED, value)).apply()

	// ---- one voice's own settings ----------------------------------------

	fun shapeValue(voice: Int, param: Int): Int? {
		if (param == Eci.VOICE_SPEED) return speed
		val key = keyOf(voice, param)
		return if (prefs.contains(key)) Eci.clampVoice(param, prefs.getInt(key, 0)) else null
	}

	fun setShapeValue(voice: Int, param: Int, value: Int) {
		if (param == Eci.VOICE_SPEED) {
			speed = value
			return
		}
		prefs.edit().putInt(keyOf(voice, param), Eci.clampVoice(param, value)).apply()
	}

	/** What has been chosen for [voice], by the number eciSetVoiceParam takes.
	 *  What is not here is left as the preset has it. */
	fun shape(voice: Int): Map<Int, Int> {
		val out = LinkedHashMap<Int, Int>()
		for (param in PER_VOICE) {
			val key = keyOf(voice, param)
			if (prefs.contains(key)) out[param] = Eci.clampVoice(param, prefs.getInt(key, 0))
		}
		out[Eci.VOICE_SPEED] = speed
		return out
	}

	fun writeShape(voice: Int, values: Map<Int, Int>) {
		val edit = prefs.edit()
		for (param in PER_VOICE) values[param]?.let { edit.putInt(keyOf(voice, param), it) }
		edit.apply()
		values[Eci.VOICE_SPEED]?.let { speed = it }
	}

	/** Whether [voice] has been changed from what it came with. */
	fun shapeIsCustom(voice: Int): Boolean = PER_VOICE.any { prefs.contains(keyOf(voice, it)) }

	/** Forgets one voice's changes. The speed is everybody's and stays. */
	fun clearShape(voice: Int) {
		val edit = prefs.edit()
		for (param in PER_VOICE) edit.remove(keyOf(voice, param))
		edit.apply()
	}

	private fun keyOf(voice: Int, param: Int) = "voice${voice}_param$param"

	/** Settings written when there was one set of them for every voice. They go
	 *  to whichever voice was in force at the time, which is where they were
	 *  heard. */
	private fun carryOverTheOldKeys() {
		val old = Eci.NUM_VOICE_PARAMS.let { 0 until it }.map { "voice_param_$it" }
		if (old.none { prefs.contains(it) }) return
		val edit = prefs.edit()
		val was = prefs.getInt(KEY_VOICE, 0).coerceIn(0, Eci.PRESET_NAMES.lastIndex)
		for (param in 0 until Eci.NUM_VOICE_PARAMS) {
			val from = "voice_param_$param"
			if (!prefs.contains(from)) continue
			val value = prefs.getInt(from, 0)
			if (param == Eci.VOICE_SPEED) {
				edit.putInt(KEY_SPEED, Eci.clampVoice(param, value))
			} else {
				edit.putInt(keyOf(was, param), Eci.clampVoice(param, value))
			}
			edit.remove(from)
		}
		edit.apply()
	}

	/** Gender was once a setting of its own. The presets already are who they
	 *  are -- Shelley is Shelley without anyone choosing female -- so stored
	 *  gender choices are forgotten and the preset's own stands. */
	private fun dropRetiredGenderKeys() {
		val edit = prefs.edit()
		var changed = false
		for (voice in Eci.PRESET_NAMES.indices) {
			val key = "voice${voice}_param${Eci.VOICE_GENDER}"
			if (prefs.contains(key)) {
				edit.remove(key)
				changed = true
			}
		}
		if (changed) edit.apply()
	}

	// ---- dictionaries ----------------------------------------------------

	fun dictionaryPath(volume: Int): String? = prefs.getString("dict_path_$volume", null)

	fun dictionaryName(volume: Int): String? = prefs.getString("dict_name_$volume", null)

	fun setDictionary(volume: Int, path: String?, name: String?) {
		val edit = prefs.edit()
		if (path == null) {
			edit.remove("dict_path_$volume").remove("dict_name_$volume")
		} else {
			edit.putString("dict_path_$volume", path).putString("dict_name_$volume", name)
		}
		edit.apply()
	}

	fun dictionaryPaths(): Map<Int, String> {
		val out = LinkedHashMap<Int, String>()
		for (volume in Eci.DICT_VOLUMES) dictionaryPath(volume)?.let { out[volume] = it }
		return out
	}

	/** Whether the file picked for [volume] is actually taught to the engine.
	 *  On unless switched off: picking a file turns its volume on. */
	fun useDictionary(volume: Int): Boolean =
		prefs.getBoolean(KEY_USE_DICT_PREFIX + volume, true)

	fun setUseDictionary(volume: Int, on: Boolean) {
		prefs.edit().putBoolean(KEY_USE_DICT_PREFIX + volume, on).apply()
	}

	// ---- regex dictionary ----------------------------------------------

	/** Whether the regex dictionary is applied. Off until a file is picked,
	 *  which turns it on. */
	var regexDictionaryEnabled: Boolean
		get() = prefs.getBoolean(KEY_REGEX_ENABLED, false)
		set(value) = prefs.edit().putBoolean(KEY_REGEX_ENABLED, value).apply()

	fun regexDictionaryPath(): String? = prefs.getString(KEY_REGEX_PATH, null)

	fun regexDictionaryName(): String? = prefs.getString(KEY_REGEX_NAME, null)

	/** The regex dictionary file if one has been picked and still exists. */
	fun regexDictionaryFile(): java.io.File? =
		regexDictionaryPath()?.let { java.io.File(it) }?.takeIf { it.exists() }

	fun setRegexDictionary(path: String?, name: String?) {
		val edit = prefs.edit()
		if (path == null) {
			edit.remove(KEY_REGEX_PATH).remove(KEY_REGEX_NAME)
		} else {
			edit.putString(KEY_REGEX_PATH, path).putString(KEY_REGEX_NAME, name)
		}
		edit.apply()
	}

	companion object {
		const val KEY_VOICE = "voice_preset"
		const val KEY_SAMPLE_RATE = "sample_rate_hz"
		const val KEY_ABBREVIATIONS = "abbreviations"
		const val KEY_SPEED = "speed"
		const val KEY_PAUSES = "pauses"
		const val KEY_PHRASE_PREDICTION = "phrase_prediction"
		const val KEY_BACKQUOTE_TAGS = "backquote_tags"
		const val KEY_REGEX_ENABLED = "regex_enabled"
		const val KEY_REGEX_PATH = "regex_path"
		const val KEY_REGEX_NAME = "regex_name"
		const val KEY_USE_DICT_PREFIX = "use_dict_"
		const val DEFAULT_SAMPLE_RATE = 11025

		/** The seven, in the order the settings screen shows them. Gender is not
		 *  among them: the presets already are who they are. */
		val SHAPE = listOf(
			Eci.VOICE_SPEED,
			Eci.VOICE_PITCH_BASELINE,
			Eci.VOICE_PITCH_FLUCTUATION,
			Eci.VOICE_HEAD_SIZE,
			Eci.VOICE_ROUGHNESS,
			Eci.VOICE_BREATHINESS,
			Eci.VOICE_VOLUME
		)

		/** The six that belong to a voice. Speed belongs to the listener. */
		val PER_VOICE = SHAPE.filter { it != Eci.VOICE_SPEED }

		/** Every one of them is a slider. */
		val SLIDERS = SHAPE
	}
}
