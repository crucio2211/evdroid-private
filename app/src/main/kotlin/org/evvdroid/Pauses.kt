package org.evvdroid

/**
 * Native pause modes, matching the three choices in Apple's ReLoq ECI policy.
 *
 * The OpenEVV ECI backend used here exposes the equivalent policy through
 * parameter 22. Keep the original text intact and let the engine decide which
 * pauses to shorten; inserting inline pause tags as well would apply the
 * policy twice.
 */
object Pauses {
	/** Keep every pause produced by the engine. */
	const val KEEP = 0

	/** Shorten only the pause at the end of the utterance. */
	const val END_ONLY = 1

	/** Shorten pauses at punctuation and at the end of the utterance. */
	const val ALL = 2
}
