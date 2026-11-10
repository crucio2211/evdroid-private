package org.evvdroid

/**
 * Shortening the gaps the engine leaves at punctuation and at the end of what
 * it was given, following the logic of the NVDA IBMTTS add-on.
 */
object Pauses {

	/** Leave the engine's own pauses alone. */
	const val KEEP = 0

	/** Shorten only the gap after the last thing said. */
	const val END_ONLY = 1

	/** Shorten the gaps at punctuation as well. */
	const val ALL = 2

	/** The marks the engine pauses at. Matching NVDA IBMTTS: -,.:;)(?! plus dashes. */
	private const val MARKS = "-,.:;)(?!–—\u0096\u0097"

	/** Closing quotes/brackets that can follow a mark. */
	private const val CLOSERS = "\"')]}»”’"

	/** A punctuation mark that counts: follows a letter, digit or space,
	 *  and followed by whitespace, slash, or the end. */
	private val AT_A_MARK = Regex("([A-Za-z0-9]|\\s)([$MARKS])([$MARKS]*?)([$CLOSERS]*(\\s|[\\\\/]|\$))")

	private const val BRIEF = "`p1"

	/**
	 * [text] with the pauses [mode] asks for. [last] says whether this is the
	 * end of what was asked for.
	 */
	fun apply(text: String, mode: Int, last: Boolean): String {
		if (mode == KEEP || text.isEmpty()) return text
		var out = text
		if (mode == ALL) {
			out = AT_A_MARK.replace(out, "$1 $BRIEF$2$3$4")
		}
		// The engine pauses at the end of an utterance whether or not anything
		// there asked it to. Matching NVDA IBMTTS: if the last character is not
		// punctuation, append `p1.
		if (last) {
			val trimmed = out.trimEnd()
			if (trimmed.isNotEmpty() && trimmed.last() !in MARKS && trimmed.last() !in CLOSERS) {
				out = "$out $BRIEF"
			}
		}
		return out
	}
}
