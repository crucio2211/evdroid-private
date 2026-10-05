package org.evvdroid

import java.io.File

/**
 * Words with pronunciations in the engine's own alphabet, applied as text.
 *
 * A `` `[ `` annotation encloses a pronunciation the engine honours as
 * written -- phones in dotted syllables with the stress digits, such as
 * `` `[.1pro.0dus] `` for the noun produce -- and it never reaches a
 * dictionary or a rule. Upstream measured all seventeen heteronyms this way
 * (docs/notes/heteronyms.md): the annotation is what is heard. So a word
 * listed here is said exactly as written, wherever it stands, with no say
 * from grammar, stress rules or dictionaries.
 *
 * Each line of the file is a word, a tab, and its phones: `produce\t.1pro.0dus`.
 * Blank lines and lines starting with `#` are ignored, and so is an entry
 * with nothing on either side. The first spelling of a word wins. Anything
 * the engine cannot parse is spoken as text rather than refused, which is
 * how a bad entry announces itself.
 *
 * Applied last, closest to the engine, after the regex dictionary: a regex
 * rewrite can still move words about, but nothing afterwards touches the
 * annotations. Text the caller wrote with its own backquotes is left alone,
 * so an annotation already there is never wrapped twice.
 */
object PhonemeDictionaries {

	private val WORD = Regex("""[\p{L}\p{N}]+(?:['-][\p{L}\p{N}]+)*""")

	private val PRESENT = Regex("`\\[[^\\]]*\\]")

	fun count(file: File): Int = try {
		load(file).size
	} catch (e: Exception) {
		0
	}

	/** Word to phones, lowercased, in file order. A pronunciation holding a
	 *  backquote or whitespace would break the annotation or the piece it
	 *  travels in, so it is refused rather than laid down broken. */
	fun load(file: File): Map<String, String> = try {
		val out = LinkedHashMap<String, String>()
		file.forEachLine { line ->
			val trimmed = line.trim()
			if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine
			val tab = trimmed.indexOf('\t')
			if (tab <= 0) return@forEachLine
			val word = trimmed.substring(0, tab).trim().lowercase()
			val phones = trimmed.substring(tab + 1).trim()
			if (word.isEmpty() || phones.isEmpty()) return@forEachLine
			if ('`' in phones || phones.any { it.isWhitespace() }) return@forEachLine
			// First spelling wins. putIfAbsent is a Java 8 default method the
			// oldest phones here predate, so this is spelled out.
			if (!out.containsKey(word)) out[word] = phones
		}
		out
	} catch (e: Exception) {
		emptyMap()
	}

	/** [text] with every listed word wrapped as `` `[phones] ``. Matching is
	 *  whole-word and case-blind; each word of the text is read once, so a
	 *  replacement is never itself replaced. */
	fun apply(text: String, map: Map<String, String>): String {
		if (text.isEmpty() || map.isEmpty()) return text
		val guarded = PRESENT.findAll(text).map { it.range }.toList()
		val out = StringBuilder(text.length + map.size * 8)
		var at = 0
		for (word in WORD.findAll(text)) {
			if (guarded.any { word.range.first in it }) continue
			val phones = map[word.value.lowercase()] ?: continue
			out.append(text, at, word.range.first)
			out.append("`[").append(phones).append(']')
			at = word.range.last + 1
		}
		out.append(text, at, text.length)
		return out.toString()
	}
}
