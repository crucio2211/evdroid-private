package org.evvdroid

import android.util.Log
import java.io.File

/**
 * External pronunciation dictionaries for the native ECI dictionary handle.
 *
 * A saved binary volume is passed unchanged to the engine's native volume
 * loader. Text dictionaries are decoded as UTF-8 when valid, otherwise as
 * Windows-1252, then encoded for the active language and staged as immutable
 * files. The native ECI bulk loader reads each complete volume into a
 * detached dictionary handle before the service publishes all selected
 * volumes together. It is not expanded into one JNI/API call per entry.
 *
 * A line is a key, a tab, and what to say instead. What to say is either a
 * respelling ("dee oe jeigh") or a pronunciation in the engine's own alphabet
 * ("`[Ekspoz1e]"). Keys are case-sensitive and remain exactly as written.
 *
 * EngineText performs the language-specific encoding, including Shift-JIS
 * and Polish paths; this reader does not guess at those byte sets.
 */
object Dictionaries {

	/** Whether [file] is a saved binary ECI volume. Binary files bypass
	 *  text decoding and are passed unchanged to eciLoadDict; text files are
	 *  normalized and loaded through ECI's native bulk-volume API. */
	fun isBinary(file: File): Boolean = try {
		file.inputStream().use { source ->
			val head = ByteArray(8192)
			val n = source.read(head)
			(0 until n).any { head[it] == 0.toByte() }
		}
	} catch (unreadable: Exception) {
		false
	}

	/** Whether [key] to [say] is safe to encode: both present, modestly
	 *  sized, and free of control characters that come out of binary reads
	 *  and malformed lines. Anything else is skipped and counted refused
	 *  rather than handed to the engine.
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
	 *  American English, engmain is British, and so on. The service only
	 *  includes a selected volume in the snapshot for that language. */
	fun languageForFile(name: String): String? {
		val match = FILE_VOLUME.find(name.trim()) ?: return null
		return LANGUAGE_NAMES[match.groupValues[1].lowercase()]
	}

	/** Writes one normalized text volume for an immutable snapshot. The native
	 *  bridge reads it into a detached ECI dictionary handle; no per-word map
	 *  is retained or rebuilt by the speech service. */
	fun writeEngineFile(file: File, language: Int, destination: File) {
		var written = 0
		java.io.BufferedOutputStream(destination.outputStream()).use { out ->
			forEachEntry(file) { entry ->
				if (!isUsable(entry.first, entry.second)) return@forEachEntry
				val keyBytes = EngineText.encode(language, entry.first)
				val sayBytes = EngineText.encode(language, entry.second)
				if (keyBytes.isEmpty() || keyBytes.size > 128 || sayBytes.isEmpty() || sayBytes.size > 512) return@forEachEntry
				val delimiter = { byte: Byte -> byte == 0.toByte() || byte == '\t'.code.toByte() || byte == '\r'.code.toByte() || byte == '\n'.code.toByte() }
				if (keyBytes.any(delimiter) || sayBytes.any(delimiter)) return@forEachEntry
				out.write(keyBytes)
				out.write('\t'.code)
				out.write(sayBytes)
				out.write('\n'.code)
				written++
			}
			// OpenEVV's native volume reader drops the final record if EOF
			// immediately follows it. Its own dictfile fixture uses a blank
			// line after the final record, so preserve that exact file format.
			if (written > 0) out.write('\n'.code)
		}
		if (written == 0) throw java.io.IOException("text dictionary has no usable entries")
		java.io.FileOutputStream(destination, true).use { it.fd.sync() }
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
