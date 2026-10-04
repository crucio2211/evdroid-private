package org.evvdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The output stage: a master volume, a soft limiter, and bass and treble,
 * applied to 16-bit samples in place.
 *
 * A signal alternating every sample is used throughout: it sits far above
 * the DC blocker's corner, so what comes out is the stage's doing rather
 * than the blocker's settling.
 */
@RunWith(AndroidJUnit4::class)
class AudioOutputTest {

	private fun alternating(samples: Int, amplitude: Int): ByteArray {
		val buffer = ByteArray(samples * 2)
		for (i in 0 until samples) {
			val value = if (i % 2 == 0) amplitude else -amplitude
			buffer[i * 2] = (value and 0xFF).toByte()
			buffer[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
		}
		return buffer
	}

	/** Samples past the DC blocker's settling, signs as written. */
	private fun settled(buffer: ByteArray, skipSamples: Int = 10): List<Int> {
		val out = ArrayList<Int>()
		var i = skipSamples * 2
		while (i + 1 < buffer.size) {
			out.add((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF))
			i += 2
		}
		return out
	}

	@Test
	fun silenceStaysSilent() {
		val buffer = ByteArray(64)
		AudioOutput.Chain(
			16000,
			AudioOutput.Snapshot(volume = 200, limiter = true, bassDb = 12, trebleDb = -12)
		).process(buffer, buffer.size)
		assertTrue(buffer.all { it == 0.toByte() })
	}

	@Test
	fun volumeScales() {
		val buffer = alternating(40, 10000)
		AudioOutput.Chain(16000, AudioOutput.Snapshot(volume = 150)).process(buffer, buffer.size)
		val got = settled(buffer)
		assertTrue(got.isNotEmpty())
		got.forEachIndexed { at, value ->
			val want = if (at % 2 == 0) 15000.0 else -15000.0
			assertEquals("sample $at", want, value.toDouble(), 200.0)
		}
	}

	@Test
	fun volumeZeroSilences() {
		val buffer = alternating(40, 20000)
		AudioOutput.Chain(16000, AudioOutput.Snapshot(volume = 0)).process(buffer, buffer.size)
		assertTrue(buffer.all { it == 0.toByte() })
	}

	@Test
	fun limiterRoundsPeaksInsteadOfLoppingThem() {
		val buffer = alternating(40, 30000)
		AudioOutput.Chain(16000, AudioOutput.Snapshot(volume = 130, limiter = true))
			.process(buffer, buffer.size)
		val peaks = settled(buffer).filterIndexed { at, _ -> at % 2 == 0 }
		assertTrue(peaks.isNotEmpty())
		// Linear would hit 39000 and lop at 32767; the shoulder lands just under.
		peaks.forEach { assertTrue("peak $it", it in 32000..32766) }
	}

	@Test
	fun limiterOffLops() {
		val buffer = alternating(40, 30000)
		AudioOutput.Chain(16000, AudioOutput.Snapshot(volume = 130, limiter = false))
			.process(buffer, buffer.size)
		val peaks = settled(buffer).filterIndexed { at, _ -> at % 2 == 0 }
		assertTrue(peaks.isNotEmpty())
		peaks.forEach { assertEquals(32767, it) }
	}

	@Test
	fun flatToneIsExact() {
		val buffer = alternating(40, 5000)
		AudioOutput.Chain(16000, AudioOutput.Snapshot(volume = 100, bassDb = 0, trebleDb = 0))
			.process(buffer, buffer.size)
		val got = settled(buffer)
		got.forEachIndexed { at, value ->
			val want = if (at % 2 == 0) 5000.0 else -5000.0
			assertEquals("sample $at", want, value.toDouble(), 100.0)
		}
	}

	@Test
	fun oddTailByteIsLeftAlone() {
		val buffer = byteArrayOf(0x10, 0x27, 0x56)
		AudioOutput.Chain(16000, AudioOutput.Snapshot(volume = 200)).process(buffer, buffer.size)
		assertEquals(0x56.toByte(), buffer[2])
	}
}
