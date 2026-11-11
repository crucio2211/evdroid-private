package org.evvdroid

import android.speech.tts.Voice
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Keep the Android latency declaration and small streaming commits in step. */
@RunWith(AndroidJUnit4::class)
class VoiceLatencyTest {

	@Test
	fun everyAdvertisedVoiceRequestsVeryLowLatency() {
		val voices = EvvTtsService().onGetVoices()
		assertFalse("the engine advertised no voices", voices.isEmpty())
		for (voice in voices) {
			assertEquals("${voice.name} did not request very-low latency",
				Voice.LATENCY_VERY_LOW, voice.latency)
		}
	}

	@Test
	fun audioIsDeliveredInSmallBoundedChunks() {
		assertEquals(1024, EvvTtsService.MIN_CHUNK)
		assertEquals(4096, EvvTtsService.MAX_CHUNK)
	}
}
