package org.evvdroid

/**
 * The three native ECI pause modes. The values are passed straight to
 * ECI_PARAM_PAUSES; Android does not rewrite text or inject pause annotations.
 */
object Pauses {
    /** Keep every native pause. */
    const val KEEP = 0

    /** Shorten only the pause at the end of the utterance. */
    const val END_ONLY = 1

    /** Shorten all native pauses. */
    const val ALL = 2
}
