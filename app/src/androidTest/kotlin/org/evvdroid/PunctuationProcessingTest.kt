package org.evvdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Punctuation spoken out instead of only paused at.
 *
 * Levels and names are NVDA's built-in symbols.dic, mark for mark: some
 * speaks the dot but leaves the comma, the bang and the question mark
 * alone, and most still leaves those plus dollar and ellipsis alone. A
 * spoken mark replaces its place -- keeping it behind its name was tried,
 * and the engine spells an isolated mark aloud, so every spoken mark came
 * out twice. At none a brief pause stands where each mark stood. Glued
 * marks survive every level, this port's one deliberate divergence from
 * NVDA.
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
			"a at b.com",
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
	fun allSpeaksThemOnce() {
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
	fun noneLeavesPausesNotWords() {
		assertEquals(
			"Hello `p1 world `p1 `p1 yes `p1",
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

	/**
	 * Why punctuation runs after the backquote strip: its pauses are ours,
	 * and what the strip takes must never include them.
	 */
	@Test
	fun pausesSurviveStripping() {
		val stripped = AntiCrash.apply("Hello, world", stripBackquotes = true)
		assertEquals(
			"Hello `p1 world",
			PunctuationProcessing.apply(stripped, PunctuationProcessing.NONE)
		)
	}
}
