package org.evvdroid

import android.util.Log
import java.io.File
import java.util.regex.PatternSyntaxException

/**
 * A user dictionary written as regular expressions.
 *
 * The volume dictionaries teach the engine single words. This one rewrites
 * the text itself, before the engine ever sees it: a line is a pattern, a
 * tab, and what to put in its place, so `NVDA<TAB>en vee dee ay` says the
 * letters and `\b(\d+)kg<TAB>$1 kilograms` expands a unit. Replacements use
 * the same $1 group references the built-in guards use.
 *
 * A line starting with # is a comment and a blank line is nothing, and a
 * pattern that does not compile is skipped and logged rather than taking the
 * whole file down with it. The file is read as UTF-8: unlike the volume
 * dictionaries it never reaches the engine's byte-oriented loader, it runs
 * on the text while it is still text.
 *
 * This applies last of the rewrites, after AntiCrash and TextFixes -- the
 * same place a user dictionary sits in NVDA, closest to the engine -- so
 * what is written here is what gets said, unmangled by the readability
 * rules. That also means a replacement runs after the backquote strip, so
 * there is no need to enable voice tags for a replacement to carry one.
 */
object RegexDictionaries {

	/** One compiled line. */
	data class Entry(val pattern: Regex, val replacement: String)

	/** A file as compiled, with what identifies it. The service holds one of
	 *  these and only recompiles when the file on disk is a different one. */
	data class Loaded(val entries: List<Entry>, val path: String, val modified: Long)

	/** Reads [file] into entries, skipping comments, blanks and patterns that
	 *  do not compile. Answers null when nothing usable came out. */
	fun load(file: File): Loaded? {
		val entries = ArrayList<Entry>()
		try {
			file.forEachLine(Charsets.UTF_8) { line ->
				entryOf(line)?.let { entries.add(it) }
			}
		} catch (unreadable: Exception) {
			Log.e(TAG, "cannot read ${file.name}", unreadable)
			return null
		}
		if (entries.isEmpty()) return null
		return Loaded(entries, file.absolutePath, file.lastModified())
	}

	/** How many usable patterns a file offers, for saying so on the settings
	 *  screen without compiling anything twice. */
	fun count(file: File): Int = try {
		var n = 0
		file.forEachLine(Charsets.UTF_8) { line -> if (entryOf(line) != null) n++ }
		n
	} catch (unreadable: Exception) {
		0
	}

	/** [text] with every entry applied in file order. A null [loaded] or an
	 *  empty text is handed back untouched. */
	fun apply(loaded: Loaded?, text: String): String {
		if (loaded == null || text.isEmpty()) return text
		var out = text
		for (entry in loaded.entries) out = entry.pattern.replace(out, entry.replacement)
		return out
	}

	/** Whether [loaded] still describes the file on disk. */
	fun isCurrent(loaded: Loaded?): Boolean {
		if (loaded == null) return false
		val file = File(loaded.path)
		return file.exists() && file.lastModified() == loaded.modified
	}

	private fun entryOf(line: String): Entry? {
		val trimmed = line.trim()
		if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
		val split = line.indexOf('\t')
		if (split <= 0) return null
		val pattern = line.substring(0, split).trim()
		val replacement = line.substring(split + 1).trim()
		if (pattern.isEmpty() || replacement.isEmpty()) return null
		return try {
			Entry(Regex(pattern), replacement)
		} catch (bad: PatternSyntaxException) {
			Log.e(TAG, "skipping bad pattern: $pattern", bad)
			null
		}
	}

	private const val TAG = "evvdroid"
}
