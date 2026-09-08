package org.evvdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one pipeline TalkBack and the sample button share.
 *
 * Stages compose in order and every stage's output reaches the engine:
 * punctuation once computed its text and never handed it on, so all levels
 * were silent, and only an end-to-end check like these can catch that kind
 * of wiring again.
 */
@RunWith(AndroidJUnit4::class)
class TextPipelineTest {

	@Test
	fun punctuationReachesTheEngine() {
		assertEquals(
			"Hello dot",
			TextPipeline.process(
				"Hello.",
				emoji = false,
				numberMode = null,
				stripBackquotes = true,
				punctLevel = PunctuationProcessing.SOME
			)
		)
	}

	@Test
	fun numbersRunBeforePunctuation() {
		assertEquals(
			"Call 1 2 3 dot",
			TextPipeline.process(
				"Call 123.",
				emoji = false,
				numberMode = NumberProcessing.DIGITS,
				stripBackquotes = true,
				punctLevel = PunctuationProcessing.SOME
			)
		)
	}

	@Test
	fun everythingOffLeavesTextAlone() {
		assertEquals(
			"Hello, world!",
			TextPipeline.process(
				"Hello, world!",
				emoji = false,
				numberMode = null,
				stripBackquotes = true,
				punctLevel = null
			)
		)
	}
}
