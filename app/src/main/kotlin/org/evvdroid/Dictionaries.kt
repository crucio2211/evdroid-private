package org.evvdroid

import android.util.Log
import java.io.File

/**
 * The pronunciation dictionaries people write for this engine.
 *
 * The engine has a loader of its own, and it does not take these: eciLoadDict
 * answers eciDictAccessError for every one of them, because what it reads is
 * IBM's own saved form and what people share is text. So the text is read here
 * and the engine is taught a word at a time, which is the same interface the
 * loader would have used and does not depend on agreeing about a file format.
 *
 * A line is a key, a tab, and what to say instead. What to say is either a
 * respelling ("dee oe jeigh") or a pronunciation in the engine's own alphabet
 * ("`[Ekspoz1e]"), and the engine takes both. The bytes are the engine's own
 * code set already -- an e-acute is one byte -- so the file is read as Latin-1
 * rather than as UTF-8, which is what the files in the wild actually are.
 */
object Dictionaries {

	/** Whether [file] looks like the engine's saved binary form rather than
	 *  text. The binary form is for eciLoadDict, which refuses these anyway;
	 *  teaching its bytes as words can fault the engine into silence, so a
	 *  file with nought bytes in its head is refused before a word of it. */
	fun isBinary(file: File): Boolean = try {
		file.inputStream().use { source ->
			val head = ByteArray(8192)
			val n = source.read(head)
			(0 until n).any { head[it] == 0.toByte() }
		}
	} catch (unreadable: Exception) {
		false
	}

	/** Whether [key] to [say] is safe to teach: both present, modestly
	 *  sized, and free of control characters that come out of binary reads
	 *  and malformed lines. Anything else is skipped and counted refused
	 *  rather than handed to the engine. */
	fun isUsable(key: String, say: String): Boolean {
		if (key.isEmpty() || say.isEmpty()) return false
		if (key.length > MAX_KEY || say.length > MAX_SAY) return false
		return !(key + say).any { it.code < 0x20 || it.code in 0x7F..0x9F }
	}

	/** The volume a shared file names, if its name says: enumain goes to
	 *  main, enuroot to root, enuabbr to abbreviations, and so for every
	 *  language prefix IBM used. Answers null when the name says nothing,
	 *  and then the row it was picked from decides. */
	fun volumeForFile(name: String): Int? {
		val match = FILE_VOLUME.find(name.trim()) ?: return null
		return when (match.groupValues[2].lowercase()) {
			"main" -> Eci.DICT_MAIN
			"root" -> Eci.DICT_ROOT
			"abbr", "abbrev", "abbreviation" -> Eci.DICT_ABBREVIATION
			else -> null
		}
	}

	/** The language a shared file names, if its name says: enumain is
	 *  American English, engmain is British, and so on. For showing, not
	 *  for routing: every picked file is taught to whichever language
	 *  engine is speaking. */
	fun languageForFile(name: String): String? {
		val match = FILE_VOLUME.find(name.trim()) ?: return null
		return LANGUAGE_NAMES[match.groupValues[1].lowercase()]
	}

	/** Teaches the engine every entry in [file]. Answers how many went in. */
	fun load(engine: EvvEngine, volume: Int, file: File): Int {
		var taught = 0
		var refused = 0
		try {
			file.forEachLine(Charsets.ISO_8859_1) { line ->
				val split = line.indexOf('\t')
				if (split > 0) {
					val key = line.substring(0, split).trim()
					val say = line.substring(split + 1).trim()
					if (isUsable(key, say)) {
						if (engine.teachWord(volume, key, say) == 0) taught++ else refused++
					} else {
						refused++
					}
				}
			}
		} catch (unreadable: Exception) {
			Log.e(TAG, "cannot read ${file.name}", unreadable)
			return taught
		}
		if (refused > 0) Log.e(TAG, "${file.name}: $refused of ${taught + refused} entries refused")
		return taught
	}

	/** How many entries a file offers, for saying so on the settings screen
	 *  without an engine to hand. */
	fun count(file: File): Int = try {
		file.useLines(Charsets.ISO_8859_1) { lines -> lines.count { it.indexOf('\t') > 0 } }
	} catch (unreadable: Exception) {
		0
	}

	private const val TAG = "evvdroid"

	private const val MAX_KEY = 128
	private const val MAX_SAY = 512

	/** A shared name like enumain.dic or esmabbr.dic: a three-letter IBM
	 *  language tag, then which volume it is. */
	private val FILE_VOLUME = Regex("""^([a-z]{3})(main|root|abbr|abbrev|abbreviation)\b""", RegexOption.IGNORE_CASE)

	private val LANGUAGE_NAMES = mapOf(
		"enu" to "American English",
		"eng" to "British English",
		"esp" to "Castilian Spanish",
		"esm" to "Latin American Spanish",
		"fra" to "French",
		"frc" to "Canadian French",
		"deu" to "German",
		"ita" to "Italian",
		"ptb" to "Brazilian Portuguese",
		"chs" to "Chinese",
		"cht" to "Chinese Traditional",
		"ctt" to "Cantonese",
		"jpn" to "Japanese",
		"kor" to "Korean",
		"fin" to "Finnish",
		"dan" to "Danish",
		"nor" to "Norwegian",
		"swe" to "Swedish",
		"nld" to "Dutch",
		"tha" to "Thai",
		"plp" to "Polish",
		"pol" to "Polish"
	)
}
