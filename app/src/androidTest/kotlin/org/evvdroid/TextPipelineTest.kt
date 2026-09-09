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
	fun emojiRunBeforeGuards() {
		assertEquals(
			"Hi grinning face",
			TextPipeline.process(
				"Hi \uD83D\uDE00",
				emoji = true,
				stripBackquotes = true
			)
		)
	}

	@Test
	fun backquotesAreStripped() {
		assertEquals(
			"vs50 hello",
			TextPipeline.process(
				"`vs50 hello",
				emoji = false,
				stripBackquotes = true
			).trim()
		)
	}

	@Test
	fun everythingOffLeavesTextAlone() {
		assertEquals(
			"Hello, world!",
			TextPipeline.process(
				"Hello, world!",
				emoji = false,
				stripBackquotes = true
			)
		)
	}
}
