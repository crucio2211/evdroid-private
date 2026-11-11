package org.evvdroid

import android.util.Log
import java.io.File

/**
 * The pronunciation dictionaries people write for this engine.
 *
 * Imported sources are either saved ECI binary volumes, copied unchanged,
 * or tab-separated text, parsed once and normalized by DictionaryData.
 * Native ECI owns the active three-volume dictionary set and all lookups;
 * Kotlin does not rewrite words while an utterance is being synthesized.
 *
 * The engine matches a key against the word as spoken, byte for byte and
 * case included: a key "fatima" does not touch "Fatima". So every key is kept
 * exactly as written, two entries that differ only in case are two entries,
 * and nothing here folds case on the way in.
 *
 * A line is a key, a tab, and what to say instead. What to say is either a
 * respelling ("dee oe jeigh") or a pronunciation in the engine's own alphabet
 * ("`[Ekspoz1e]"), and the engine takes both. The bytes are the engine's own
 * code set already -- an e-acute is one byte -- so the file is read as
 * Windows-1252 when it is not UTF-8, which shared files commonly use.
 *
 * That is the Western byte set, so a file for Polish or Japanese is not read
 * right here. Neither has a dictionary anyone shares, and guessing at a form
 * for one would be inventing a format rather than reading one.
 */
object Dictionaries {

	private data class ParsedCache(
		val length: Long,
		val modified: Long,
		val entries: Map<String, Pair<String, String>>
	)
	private val parseCache = java.util.concurrent.ConcurrentHashMap<String, ParsedCache>()

	/** Invalidates a parsed copy when an imported file is replaced in place. */
	fun invalidate(file: File) {
		parseCache.remove(file.absolutePath)
	}

	/** Whether [file] looks like the engine's saved binary form rather than
	 *  text. Its bytes are never taught as words, which can fault the engine
	 *  into silence: a file with nought bytes in its head goes to the
	 *  native snapshot builder unchanged. */
	fun isBinary(file: File): Boolean = try {
		file.inputStream().use { source ->
			val head = ByteArray(8192)
			val n = source.read(head)
			(0 until n).any { head[it] == 0.toByte() }
		}
	} catch (unreadable: Exception) {
		false
	}


	/** Whether [key] and [say] are valid entries for a native snapshot:
	 *  both are present, modestly sized, and free of unsafe control characters.
	 *
	 *  Only the C0 controls and DEL count. Windows-1252 maps the C1 byte range
	 *  to letters and marks (curly apostrophe, oe, s caron) rather than controls. */
	fun isUsable(key: String, say: String): Boolean {
		if (key.isEmpty() || say.isEmpty()) return false
		if (key.length > MAX_KEY || say.length > MAX_SAY) return false
		return !(key + say).any { it.code < 0x20 || it.code == 0x7F }
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




	/** One key and what to say, or null when it is no entry.
	 *  Accepts tab separation or whitespace, ignores comments (#, //, ;),
	 *  and ensures phoneme entries starting with [ carry the required backtick. */
	fun splitEntry(rawLine: String): Pair<String, String>? {
		var line = rawLine.trim()
		if (line.startsWith('\uFEFF')) line = line.substring(1).trim()
		if (line.isEmpty() || line.startsWith('#') || line.startsWith("//") || line.startsWith(";")) {
			return null
		}
		val tab = line.indexOf('\t')
		val (rawKey, rawSay) = if (tab > 0) {
			line.substring(0, tab).trim() to line.substring(tab + 1).trim()
		} else {
			val ws = line.indexOfFirst { it.isWhitespace() }
			if (ws <= 0) return null
			line.substring(0, ws).trim() to line.substring(ws + 1).trim()
		}
		if (rawKey.isEmpty() || rawSay.isEmpty()) return null
		val say = if (rawSay.startsWith("[") && rawSay.endsWith("]") && !rawSay.startsWith("`[")) {
			"`$rawSay"
		} else {
			rawSay
		}
		return rawKey to say
	}

	/** Reads ordinary one-entry lines, packed tab-separated pairs, and a malformed
	 *  shared dictionary line where a phoneme's closing bracket and the next key
	 *  were joined without a separator. */
	private fun forEachEntry(file: File, action: (Pair<String, String>) -> Unit) {
		forEachLineSafe(file) { line ->
			val glued = GLUED_PHONEME_PAIR.matchEntire(line)
			if (glued != null) {
				action(glued.groupValues[1] to glued.groupValues[2])
				action(glued.groupValues[3] to glued.groupValues[4])
				return@forEachLineSafe
			}
			val fields = line.split('\t')
			if (fields.size > 2 && fields.size % 2 == 0) {
				var i = 0
				while (i + 1 < fields.size) {
					splitEntry(fields[i] + "\t" + fields[i + 1])?.let(action)
					i += 2
				}
			} else {
				splitEntry(line)?.let(action)
			}
		}
	}

	private fun forEachLineSafe(file: File, action: (String) -> Unit) {
		val bytes = file.readBytes()
		val charset = if (isUtf8(bytes)) Charsets.UTF_8 else java.nio.charset.Charset.forName("windows-1252")
		var text = String(bytes, charset)
		if (text.startsWith('\uFEFF')) {
			text = text.substring(1)
		}
		text.lineSequence().forEach(action)
	}

	private fun isUtf8(bytes: ByteArray): Boolean {
		if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
			return true
		}
		return try {
			val decoder = Charsets.UTF_8.newDecoder()
				.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
				.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
			decoder.decode(java.nio.ByteBuffer.wrap(bytes))
			true
		} catch (_: Exception) {
			false
		}
	}

	/** How many usable entries a file offers, for saying so on the settings
	 *  screen without an engine to hand. */
	fun count(file: File): Int {
		var count = 0
		try {
			forEachEntry(file) { entry -> if (isUsable(entry.first, entry.second)) count++ }
		} catch (unreadable: Exception) {
			Log.e(TAG, "cannot count ${file.name}", unreadable)
			return 0
		}
		return count
	}

	private const val TAG = "evvdroid"

	private const val MAX_KEY = 128
	private const val MAX_SAY = 512
	private val GLUED_PHONEME_PAIR = Regex(
		"""^([^\t]+)\t(`?\[[^\]]+\])([\p{L}\p{N}][^\t]*)\t(.+)$"""
	)

	/** A shared name like enumain.dic or esmabbr.dic: a three-letter IBM
	 *  language tag, then which volume it is. */
	private val FILE_VOLUME = Regex("""^([a-z]{3})(main|root|abbr|abbrev|abbreviation)\b""", RegexOption.IGNORE_CASE)

	/** The engine language a shared file names, if its tag is one this app
	 *  both speaks and links: enumain is American English and so on. Answers
	 *  null when the name says nothing usable, and then the importer asks. */
	fun engineForFile(name: String, available: IntArray): Int? {
		val match = FILE_VOLUME.find(name.trim()) ?: return null
		val engine = TAG_ENGINE[match.groupValues[1].lowercase()] ?: return null
		return engine.takeIf { it in available }
	}

	private val TAG_ENGINE = mapOf(
		"enu" to 0x00010000, "eng" to 0x00010001,
		"esp" to 0x00020000, "esm" to 0x00020001,
		"fra" to 0x00030000, "frc" to 0x00030001,
		"deu" to 0x00040000, "ita" to 0x00050000,
		"ptb" to 0x00070000, "fin" to 0x00090000,
		"jpn" to 0x00080000, "pol" to 0x00110000, "plp" to 0x00110000
	)

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
