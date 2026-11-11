package org.evvdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The three native ECI pause policies, matching Apple's mode mapping:
 * 0 keeps pauses, 1 shortens the utterance-end pause, 2 also shortens
 * pauses at punctuation. These tests exercise the engine, not text rewriting.
 */
@RunWith(AndroidJUnit4::class)
class PausesTest {

	@Test
	fun modesUseTheNativeEciValues() {
		assertEquals(0, Pauses.KEEP)
		assertEquals(1, Pauses.END_ONLY)
		assertEquals(2, Pauses.ALL)
	}

	@Test
	fun nativeModesShortenOnlyTheSelectedPauseClasses() {
		val e = EvvEngine.open(ENGLISH)
		assertNotNull(e)
		try {
			e!!.setSampleRate(11025)
			e.applyVoice(0)

			val kept = say(e, Pauses.KEEP)
			val endOnly = say(e, Pauses.END_ONLY)
			val all = say(e, Pauses.ALL)

			android.util.Log.i(
				"evvpause",
				"native samples: keep=$kept, end-only=$endOnly, all=$all"
			)
			assertTrue("keep mode produced no audio", kept > 0)
			assertTrue("end-only mode did not shorten the final pause: $kept -> $endOnly", endOnly in 1 until kept)
			assertTrue("all mode did not shorten punctuation pauses as well: $endOnly -> $all", all in 1 until endOnly)
			// Audio must remain substantial; this is pause removal, not dropped speech.
			assertTrue("native pause mode removed too much audio: $kept -> $all", all > kept / 2)
		} finally {
			e?.close()
		}
	}

	private fun say(e: EvvEngine, mode: Int): Int {
		e.setPauseMode(mode)
		if (!e.speak(SENTENCE)) return -1
		var total = 0
		val buffer = ByteArray(4096)
		while (true) {
			val n = e.read(buffer)
			if (n <= 0) break
			total += n
		}
		return total
	}

	private companion object {
		const val ENGLISH = 0x00010000
		const val SENTENCE = "One, two, three. Four, five, six. Seven, eight"
	}
}
