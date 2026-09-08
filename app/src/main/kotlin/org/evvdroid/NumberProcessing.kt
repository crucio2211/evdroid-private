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
 * Only standalone runs qualify: a run glued to letters is somebody else's
 * business, and a run with number punctuation inside it is a decimal, a
 * grouped thousand, a time, a date or a range, so 3.14, 1,024, 2:30,
 * 2026-09-07 and teamtalk5 come through exactly as they went in. But
 * punctuation merely leaning on the edge -- the full stop ending
 * "call 95266261." -- is not part of the number and never saved it from
 * regrouping, which is what used to let millions through.
 *
 * This runs first of all, before AntiCrash: grouping canonicalises the
 * digits so every guard below sees one shape of them.
 */
object NumberProcessing {

	const val DIGITS = 0
	const val PAIRS = 1
	const val TRIPLETS = 2
	const val QUADRUPLETS = 3

	/** A run of digits and number punctuation, standing clear of letters:
	 *  the edges are judged after, not here. */
	private val CHUNK = Regex("""(?<![A-Za-z\d_.,:/-])[\d.,:/-]+(?![A-Za-z\d_.,:/-])""")

	private const val SEPS = ".,:/-"

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
		return CHUNK.replace(text) { match ->
			val core = match.value.trim { it in SEPS }
			if (core.isEmpty() || !core.all { it.isDigit() } || core.length <= size) {
				return@replace match.value
			}
			val leading = match.value.takeWhile { it in SEPS }
			val trailing = match.value.takeLastWhile { it in SEPS }
			leading + core.toCharArray().joinToString(" ") + trailing
		}
	}
}
