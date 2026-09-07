package org.evvdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Punctuation spoken out instead of paused at, or gone altogether.
 *
 * The level checks say each mark is spoken at its level and left below it,
 * and the glued checks say what no level may touch: contractions, decimals,
 * thousands, times and hyphenated words keep their marks everywhere.
 */
@RunWith(AndroidJUnit4::class)
class PunctuationProcessingTest {

	@Test
	fun someSpeaksTheSentenceMarks() {
		assertEquals(
			"Hello dot Are you OK question mark Yes exclamation mark",
			PunctuationProcessing.apply("Hello. Are you OK? Yes!", PunctuationProcessing.SOME)
		)
	}

	@Test
	fun belowLevelIsLeftForTheEngine() {
		assertEquals(
			"Hello, world dot a@b",
			PunctuationProcessing.apply("Hello, world. a@b", PunctuationProcessing.SOME)
		)
	}

	@Test
	fun mostSpeaksTheProseMarks() {
		assertEquals(
			"Hello comma world dot",
			PunctuationProcessing.apply("Hello, world.", PunctuationProcessing.MOST)
		)
		assertEquals(
			"left paren s right paren",
			PunctuationProcessing.apply("(s)", PunctuationProcessing.MOST)
		)
	}

	@Test
	fun allSpeaksTheRest() {
		assertEquals(
			"a at b.com",
			PunctuationProcessing.apply("a@b.com", PunctuationProcessing.ALL)
		)
		assertEquals(
			"C plus plus",
			PunctuationProcessing.apply("C++", PunctuationProcessing.ALL)
		)
		assertEquals(
			"12 slash 05",
			PunctuationProcessing.apply("12/05", PunctuationProcessing.ALL)
		)
	}

	/** A trailing dot after an abbreviation still stands alone, so e.g.
	 *  ends spoken even where its inner dot survives. */
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
