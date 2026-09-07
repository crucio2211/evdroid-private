package org.evvdroid

/**
 * Numbers read the way the listener asks.
 *
 * Left alone the engine reads 1234 as a number, twelve thirty-four's
 * thirteen-thousand cousin. Sometimes that is what is wanted and sometimes
 * a phone number or a code is, which wants its digits apart or in small
 * groups instead.
 *
 * Only standalone runs qualify: a run glued to letters or to number
 * punctuation is somebody else's business, so 3.14, 1,024, 2:30,
 * 2026-09-07 and teamtalk5 come through exactly as they went in, for the
 * clock, the thousands and the readability fixes to deal with.
 *
 * This runs first of all, before AntiCrash: grouping canonicalises the
 * digits so every guard below sees one shape of them.
 */
object NumberProcessing {

	const val DIGITS = 0
	const val PAIRS = 1
	const val TRIPLETS = 2
	const val QUADRUPLETS = 3

	private val RUN = Regex("""(?<![\w.,:/-])\d+(?![\w.,:/-])""")

	/**
	 * [text] with every standalone digit run regrouped for [mode]: apart,
	 * in pairs, in threes or in fours. Grouping runs left to right with a
	 * short tail of its own, so 12345 in pairs is 12 34 5.
	 */
	fun apply(text: String, mode: Int): String {
		if (text.isEmpty()) return text
		val size = when (mode) {
			PAIRS -> 2
			TRIPLETS -> 3
			QUADRUPLETS -> 4
			else -> 1
		}
		return RUN.replace(text) { match ->
			if (size == 1) match.value.toCharArray().joinToString(" ")
			else group(match.value, size)
		}
	}

	private fun group(digits: String, size: Int): String {
		if (digits.length <= size) return digits
		val out = StringBuilder()
		var at = 0
		while (at < digits.length) {
			if (at > 0) out.append(' ')
			out.append(digits.substring(at, minOf(at + size, digits.length)))
			at += size
		}
		return out.toString()
	}
}
