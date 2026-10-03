package org.evvdroid

import kotlin.math.sqrt

/**
 * Output gain tied to the sample rate.
 *
 * The engine's voice is voiced for 11 kHz; at higher native rates the same
 * speech comes out quieter to the ear, so anything above 11 kHz gets a
 * loudness lift of sqrt(rate / 11025) -- the same power-per-band reasoning
 * the engine's own noise path uses -- capped at 2x. 8 kHz and 11 kHz pass
 * through untouched.
 *
 * Applied to 16-bit samples with saturation rather than wrapping, so a loud
 * peak clips cleanly instead of bursting into noise.
 */
object AudioGain {

	const val REFERENCE_HZ = 11025
	const val MAX_GAIN = 2.0f

	/** Gain for [rateHz]: 1.0 at or below 11 kHz, rising above it. */
	fun gainFor(rateHz: Int): Float {
		if (rateHz <= REFERENCE_HZ) return 1.0f
		return sqrt(rateHz.toFloat() / REFERENCE_HZ).coerceIn(1.0f, MAX_GAIN)
	}

	/** Multiply the 16-bit little-endian samples in [buffer] up to [n] bytes
	 *  by [gain], saturating at the short range. A stray odd tail byte is
	 *  left alone, and 1.0 is a no-op. */
	fun apply(buffer: ByteArray, n: Int, gain: Float) {
		if (gain == 1.0f) return
		var i = 0
		val end = n - (n % 2)
		while (i < end) {
			val lo = buffer[i].toInt() and 0xFF
			val hi = buffer[i + 1].toInt()
			val sample = (hi shl 8) or lo
			var v = (sample * gain).toInt()
			if (v > 32767) v = 32767 else if (v < -32768) v = -32768
			buffer[i] = (v and 0xFF).toByte()
			buffer[i + 1] = ((v shr 8) and 0xFF).toByte()
			i += 2
		}
	}
}
