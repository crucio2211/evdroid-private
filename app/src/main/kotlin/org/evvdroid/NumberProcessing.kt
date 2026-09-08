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
 * 2026-09-07 and teamtalk5 come through exactly as they went in. A colon
 * only glues a clock time; anywhere else it is a boundary, so 12:3000000
 * regroups its long side instead of leaking millions. But punctuation
 * merely leaning on the edge -- the full stop ending "call 95266261." --
 * is not part of the number and never saved it from regrouping, which is
 * what used to let millions through.
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

	/** A thousands grouping is one number wearing commas: 1,024 and
	 *  95,266,261 read their digits, so they follow the mode instead of
	 *  leaking millions through. */
	private val THOUSANDS = Regex("""^\d{1,3}(,\d{3})+$""")

	/** A clock time reads whole in every mode: 2:30 is two thirty, never
	 *  two three oh. Any other colon is a boundary, not glue. */
	private val TIME = Regex("""^\d{1,2}:\d{2}(:\d{2})?$""")

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
			val v = match.value
			val leading = v.takeWhile { it in SEPS }
			val trailing = v.takeLastWhile { it in SEPS }
			val core = v.trim { it in SEPS }
			if (core.isEmpty()) {
				return@replace v
			}
			val out = if (':' in core && !TIME.matches(core)) {
				core.split(':').joinToString(":") { regroup(it, size) }
			} else {
				regroup(core, size)
			}
			leading + out + trailing
		}
	}

	/** [part] regrouped, or itself when it is no plain run: short, mixed,
	 *  or a thousands grouping short enough to read whole. */
	private fun regroup(part: String, size: Int): String {
		val digits = if (THOUSANDS.matches(part)) part.replace(",", "") else part
		if (!digits.all { it.isDigit() } || digits.length <= size) return part
		return digits.toCharArray().joinToString(" ")
	}
}
