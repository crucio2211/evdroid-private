package org.evvdroid

/**
 * The marks the engine spells aloud when it meets them raw.
 *
 * `@` always becomes the word `at`, padded with spaces so an address reads
 * as words: user at example com. Saying "at sign" instead was never asked
 * for and reads badly in every address it lands in.
 *
 * Dots and pluses are silenced only with punctuation off, where they would
 * otherwise be spelled: dots between lowercase letters become spaces, so
 * example.com reads as two words, and a plus becomes a space anywhere.
 * Kept are the dots that carry meaning: at a sentence end, before
 * whitespace, a digit, an uppercase letter or a closing mark, and between
 * digits. Initials read the same spaced as dotted, and an ellipsis becomes
 * the pause it means.
 *
 * Runs after TextFixes, which must not see the spaces added here as gaps
 * before marks, and before PunctuationProcessing, which never sees an `@`
 * anymore and only meets the dots and pluses when they are wanted spoken.
 */
object SymbolWords {

	private const val CLOSERS = "\"')]}»”’"

	fun apply(text: String, silenceDotsPlus: Boolean): String {
		if (text.isEmpty()) return text
		val out = StringBuilder(text.length + 16)
		var i = 0
		while (i < text.length) {
			val c = text[i]
			when {
				c == '@' -> {
					pad(out)
					out.append("at ")
				}
				silenceDotsPlus && c == '.' && !keepDot(text, i) -> out.append(' ')
				silenceDotsPlus && c == '+' -> out.append(' ')
				else -> out.append(c)
			}
			i++
		}
		return out.toString().replace(Regex(" {2,}"), " ").trim()
	}

	private fun pad(out: StringBuilder) {
		if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
	}

	/** A dot that ends something, numbers something, or opens a new
	 *  sentence or component. Anything else is inside a word the engine
	 *  would spell. */
	private fun keepDot(text: String, at: Int): Boolean {
		if (at + 1 >= text.length) return true
		val next = text[at + 1]
		if (next.isWhitespace()) return true
		if (next.isDigit()) return true
		if (next.isUpperCase()) return true
		return next in CLOSERS
	}
}
