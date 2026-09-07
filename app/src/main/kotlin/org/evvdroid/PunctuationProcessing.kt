package org.evvdroid

/**
 * Punctuation spoken out instead of paused at, or gone altogether.
 *
 * Left alone the engine pauses at a mark and says nothing: comma is a gap.
 * These are NVDA's symbol levels, ported to rewrites -- none speaks no
 * mark, some speaks the sentence marks, most speaks those and the prose
 * marks, all speaks everything listed -- where speaking a mark means
 * writing its name in plain words for the engine to read.
 *
 * A mark only counts standing alone: anything glued between two word
 * characters by one of ' - . , : stays put, so don't stays don't, 3.14
 * stays 3.14 and well-known stays hyphenated, while hello, world and even
 * a@b.com do not. At none the standalone marks are dropped; above none a
 * mark below the level is left for the engine's default, the way an
 * unlisted mark always is.
 *
 * This runs after numbers and before AntiCrash: the names it writes are
 * ordinary words, which is what every guard below wants to see.
 */
object PunctuationProcessing {

	const val NONE = 0
	const val SOME = 1
	const val MOST = 2
	const val ALL = 3

	/** A mark to the words that say it and the lowest level that speaks it. */
	private val WORDS: Map<Char, Pair<String, Int>> = mapOf(
		'.' to ("dot" to SOME),
		'?' to ("question mark" to SOME),
		'!' to ("exclamation mark" to SOME),
		',' to ("comma" to MOST),
		';' to ("semicolon" to MOST),
		':' to ("colon" to MOST),
		'\'' to ("apostrophe" to MOST),
		'"' to ("quote" to MOST),
		'-' to ("dash" to MOST),
		'–' to ("dash" to MOST),
		'—' to ("dash" to MOST),
		'…' to ("ellipsis" to MOST),
		'(' to ("left paren" to MOST),
		')' to ("right paren" to MOST),
		'@' to ("at" to ALL),
		'#' to ("hash" to ALL),
		'$' to ("dollar" to ALL),
		'%' to ("percent" to ALL),
		'&' to ("ampersand" to ALL),
		'*' to ("asterisk" to ALL),
		'+' to ("plus" to ALL),
		'=' to ("equals" to ALL),
		'/' to ("slash" to ALL),
		'\\' to ("backslash" to ALL),
		'|' to ("bar" to ALL),
		'<' to ("less" to ALL),
		'>' to ("greater" to ALL),
		'~' to ("tilde" to ALL),
		'^' to ("caret" to ALL),
		'_' to ("underscore" to ALL),
		'{' to ("left brace" to ALL),
		'}' to ("right brace" to ALL),
		'[' to ("left bracket" to ALL),
		']' to ("right bracket" to ALL),
		'•' to ("bullet" to ALL),
		'·' to ("middle dot" to ALL)
	)

	fun apply(text: String, level: Int): String {
		if (text.isEmpty()) return text
		val out = StringBuilder(text.length)
		var i = 0
		while (i < text.length) {
			val c = text[i]
			val word = WORDS[c]
			if (word == null || glued(text, i, c)) {
				out.append(c)
			} else if (level == NONE) {
				// Dropped: no word, no pause, nothing.
			} else if (word.second <= level) {
				if (out.isEmpty() || !out.last().isWhitespace()) out.append(' ')
				out.append(word.first)
				if (i + 1 >= text.length || !text[i + 1].isWhitespace()) out.append(' ')
			} else {
				out.append(c)
			}
			i += if (Character.isHighSurrogate(c) && i + 1 < text.length) 2 else 1
		}
		return out.toString().trim()
	}

	/** Whether [c] at [at] is glued inside a word: a word character either
	 *  side and a mark that joins rather than separates. Anything else
	 *  stands alone, including an @ or a / between letters. */
	private fun glued(text: String, at: Int, c: Char): Boolean {
		if (c !in GLUE) return false
		val before = at > 0 && isWordChar(text[at - 1])
		val after = at + 1 < text.length && isWordChar(text[at + 1])
		return before && after
	}

	private val GLUE = setOf('\'', '-', '.', ',', ':')

	private fun isWordChar(c: Char): Boolean =
		c == '_' || c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
}
