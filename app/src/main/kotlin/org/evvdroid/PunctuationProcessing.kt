package org.evvdroid

/**
 * Punctuation spoken out instead of only paused at, but never taken from
 * the synthesiser.
 *
 * Levels and names are NVDA's built-in symbols.dic, mark for mark: some
 * speaks the dot but leaves the comma, the bang and the question mark
 * alone, and most still leaves bang, dollar, comma, question and ellipsis
 * alone. Speaking a mark writes its NVDA name in plain words -- and keeps
 * the mark itself behind it, so the synthesiser still sees every pause it
 * was going to make. At none the marks become brief pauses instead of
 * words, so the rhythm stays and nothing is spelled.
 *
 * One deliberate divergence: a mark glued inside a word by ' - . , or :
 * stays put at every level, so don't stays don't, 3.14 stays 3.14 and
 * well-known stays hyphenated. NVDA would spell those out; here
 * readability wins.
 *
 * This runs after AntiCrash, so the guards see words as typed, and after
 * TextFixes, so its letter-digit rule never walks into the `p1 left here
 * and turns it into a spoken `p 1. The kept marks still reach Pauses and
 * get their pauses shortened like any other mark.
 */
object PunctuationProcessing {

	const val NONE = 0
	const val SOME = 1
	const val MOST = 2
	const val ALL = 3

	/** A mark to the words that say it and the lowest level that speaks it.
	 *  Levels and names are NVDA's built-in symbols.dic, mark for mark. */
	private val WORDS: Map<Char, Pair<String, Int>> = mapOf(
		'.' to ("dot" to SOME),
		'%' to ("percent" to SOME),
		'&' to ("and" to SOME),
		'*' to ("star" to SOME),
		'/' to ("slash" to SOME),
		'@' to ("at" to SOME),
		'+' to ("plus" to SOME),
		'=' to ("equals" to SOME),
		'<' to ("less" to SOME),
		'>' to ("greater" to SOME),
		'•' to ("bullet" to SOME),
		'"' to ("quote" to MOST),
		'(' to ("left paren" to MOST),
		')' to ("right paren" to MOST),
		'-' to ("dash" to MOST),
		'–' to ("en dash" to MOST),
		'—' to ("em dash" to MOST),
		':' to ("colon" to MOST),
		';' to ("semi" to MOST),
		'[' to ("left bracket" to MOST),
		']' to ("right bracket" to MOST),
		'^' to ("caret" to MOST),
		'_' to ("line" to MOST),
		'{' to ("left brace" to MOST),
		'}' to ("right brace" to MOST),
		'|' to ("bar" to MOST),
		'~' to ("tilda" to MOST),
		'·' to ("middle dot" to MOST),
		'!' to ("bang" to ALL),
		'$' to ("dollar" to ALL),
		',' to ("comma" to ALL),
		'?' to ("question" to ALL),
		'…' to ("dot dot dot" to ALL),
		'\'' to ("tick" to ALL)
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
				// A brief pause where the mark stood: the rhythm stays and
				// nothing is spelled. Annotations are ours, added after the
				// backquote strip, so they always survive.
				pad(out)
				out.append(BRIEF_PAUSE)
				if (i + 1 >= text.length || !text[i + 1].isWhitespace()) out.append(' ')
			} else if (word.second <= level) {
				pad(out)
				out.append(word.first).append(' ').append(c)
				if (i + 1 >= text.length || !text[i + 1].isWhitespace()) out.append(' ')
			} else {
				out.append(c)
			}
			i += if (Character.isHighSurrogate(c) && i + 1 < text.length) 2 else 1
		}
		return out.toString().trim()
	}

	private fun pad(out: StringBuilder) {
		if (!out.isEmpty() && !out.last().isWhitespace()) out.append(' ')
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

	/** The pause left where a mark stood at none: brief, like the JAWS-like
	 *  pauses, and added after the backquote strip so it always survives. */
	private const val BRIEF_PAUSE = "`p1"

	private fun isWordChar(c: Char): Boolean =
		c == '_' || c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
}
