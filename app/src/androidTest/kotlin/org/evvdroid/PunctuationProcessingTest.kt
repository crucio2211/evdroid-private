package org.evvdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Punctuation spoken out instead of paused at, or gone altogether.
 *
 * Levels and names are NVDA's built-in symbols.dic, mark for mark: some
 * speaks the dot but leaves the comma, the bang and the question mark to
 * the synthesiser, and most still leaves those plus dollar and ellipsis
 * alone. Glued marks survive every level, which is this port's one
 * deliberate divergence from NVDA.
 */
@RunWith(AndroidJUnit4::class)
class PunctuationProcessingTest {

	@Test
	fun someSpeaksDotsNotBangs() {
		assertEquals(
			"Hello dot Are you OK? Yes!",
			PunctuationProcessing.apply("Hello. Are you OK? Yes!", PunctuationProcessing.SOME)
		)
	}

	@Test
	fun someSpeaksTheSmallMarks() {
		assertEquals(
			"a plus b equals c",
			PunctuationProcessing.apply("a+b=c", PunctuationProcessing.SOME)
		)
		assertEquals(
			"100 percent and more",
			PunctuationProcessing.apply("100% & more", PunctuationProcessing.SOME)
		)
		assertEquals(
			"a at b dot com",
			PunctuationProcessing.apply("a@b.com", PunctuationProcessing.SOME)
		)
	}

	@Test
	fun belowLevelIsLeftForTheEngine() {
		assertEquals(
			"Hello, world dot a at b",
			PunctuationProcessing.apply("Hello, world. a@b", PunctuationProcessing.SOME)
		)
	}

	@Test
	fun mostLeavesCommaBangAndQuestion() {
		assertEquals(
			"Wait, what? No!",
			PunctuationProcessing.apply("Wait, what? No!", PunctuationProcessing.MOST)
		)
		assertEquals(
			"Hello, world dot",
			PunctuationProcessing.apply("Hello, world.", PunctuationProcessing.MOST)
		)
		assertEquals(
			"left paren s right paren",
			PunctuationProcessing.apply("(s)", PunctuationProcessing.MOST)
		)
	}

	@Test
	fun allSpeaksThem() {
		assertEquals(
			"Wait comma what question No bang",
			PunctuationProcessing.apply("Wait, what? No!", PunctuationProcessing.ALL)
		)
		assertEquals(
			"rock tick n tick roll",
			PunctuationProcessing.apply("rock 'n' roll", PunctuationProcessing.ALL)
		)
		assertEquals(
			"12 slash 05",
			PunctuationProcessing.apply("12/05", PunctuationProcessing.ALL)
		)
	}

	@Test
	fun gluedMarksSurviveEveryLevel() {
		for (text in listOf("don't", "3.14", "1,024", "2:30", "well-known")) {
			assertEquals(text, PunctuationProcessing.apply(text, PunctuationProcessing.SOME))
			assertEquals(text, PunctuationProcessing.apply(text, PunctuationProcessing.MOST))
			assertEquals(text, PunctuationProcessing.apply(text, PunctuationProcessing.ALL))
		}
		assertEquals("e.g dot", PunctuationProcessing.apply("e.g.", PunctuationProcessing.SOME))
	}

	@Test
	fun noneDropsTheStandaloneMarks() {
		assertEquals(
			"Hello world yes",
			PunctuationProcessing.apply("Hello, world! (yes)", PunctuationProcessing.NONE)
		)
		assertEquals("don't", PunctuationProcessing.apply("don't", PunctuationProcessing.NONE))
		assertEquals("3.14", PunctuationProcessing.apply("3.14", PunctuationProcessing.NONE))
	}

	@Test
	fun ordinaryTextIsUntouched() {
		for (text in listOf("Hello world", "Navigate up", "")) {
			assertEquals(text, PunctuationProcessing.apply(text, PunctuationProcessing.ALL))
		}
	}
}
