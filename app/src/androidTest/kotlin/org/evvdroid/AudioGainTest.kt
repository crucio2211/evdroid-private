package org.evvdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gain tied to the sample rate: 1.0 at or below 11 kHz, rising above it,
 * applied to 16-bit samples with saturation instead of wraparound.
 */
@RunWith(AndroidJUnit4::class)
class AudioGainTest {

	@Test
	fun elevenKhzAndBelowAreUntouched() {
		assertEquals(1.0f, AudioGain.gainFor(8000))
		assertEquals(1.0f, AudioGain.gainFor(11025))
	}

	@Test
	fun higherRatesLift() {
		assertEquals(1.2f, AudioGain.gainFor(16000), 0.01f)
		assertEquals(1.41f, AudioGain.gainFor(22050), 0.01f)
		assertEquals(2.0f, AudioGain.gainFor(44100), 0.01f)
		assertEquals(2.0f, AudioGain.gainFor(48000), 0.01f)
	}

	@Test
	fun silenceStaysSilent() {
		val buffer = ByteArray(8)
		AudioGain.apply(buffer, buffer.size, 1.5f)
		assertTrue(buffer.all { it == 0.toByte() })
	}

	@Test
	fun samplesScaleAndSaturate() {
		fun sample(value: Int): ByteArray =
			byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

		fun readback(buffer: ByteArray): Int =
			((buffer[1].toInt() shl 8) or (buffer[0].toInt() and 0xFF))

		val half = sample(10000)
		AudioGain.apply(half, half.size, 1.5f)
		assertEquals(15000, readback(half))

		val loud = sample(30000)
		AudioGain.apply(loud, loud.size, 2.0f)
		assertEquals(32767, readback(loud))

		val quiet = sample(-30000)
		AudioGain.apply(quiet, quiet.size, 2.0f)
		assertEquals(-32768, readback(quiet))
	}

	@Test
	fun gainOfOneLeavesBytesAlone() {
		val buffer = byteArrayOf(0x12, 0x34, 0x56)
		AudioGain.apply(buffer, buffer.size, 1.0f)
		assertEquals(0x12.toByte(), buffer[0])
		assertEquals(0x34.toByte(), buffer[1])
		assertEquals(0x56.toByte(), buffer[2])
	}
}
