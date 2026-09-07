package org.evvdroid

/**
 * Numbers read the way the listener asks.
 *
 * A run no longer than the mode reads whole, as the engine would say it:
 * pairs leaves twelve alone, triplets leaves one hundred twenty-three
 * alone. A run
 * longer than the mode falls back to digits, so 1234 in pairs is 1 2 3 4
 * while in quadruplets it stays one number. Digits always come apart, and
 * with the switch off the synthesiser decides everything.
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
	 * [text] with every standalone digit run either left whole or spelled
	 * out for [mode].
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
			val digits = match.value
			if (digits.length <= size) digits
			else digits.toCharArray().joinToString(" ")
		}
	}
}
