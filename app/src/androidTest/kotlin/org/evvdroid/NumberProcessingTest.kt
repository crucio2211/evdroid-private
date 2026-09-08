package org.evvdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Numbers read the way the listener asks.
 *
 * Each check says what a standalone run becomes: whole up to the mode,
 * digits past it. The untouched checks say what is somebody else's
 * business: decimals, grouped thousands, times, dates and letter-glued
 * tokens are not numbers standing alone.
 */
@RunWith(AndroidJUnit4::class)
class NumberProcessingTest {

	@Test
	fun digitsAlwaysComeApart() {
		assertEquals("Call 1 2 3 4 5", NumberProcessing.apply("Call 12345", NumberProcessing.DIGITS))
		assertEquals("Room 1 0 1", NumberProcessing.apply("Room 101", NumberProcessing.DIGITS))
		assertEquals("7", NumberProcessing.apply("7", NumberProcessing.DIGITS))
	}

	@Test
	fun pairsKeepTwoAndSpellLonger() {
		assertEquals("12", NumberProcessing.apply("12", NumberProcessing.PAIRS))
		assertEquals("1 2 3", NumberProcessing.apply("123", NumberProcessing.PAIRS))
		assertEquals("1 2 3 4 5 6", NumberProcessing.apply("123456", NumberProcessing.PAIRS))
	}

	@Test
	fun tripletsKeepThreeAndSpellLonger() {
		assertEquals("123", NumberProcessing.apply("123", NumberProcessing.TRIPLETS))
		assertEquals("1 2 3 4", NumberProcessing.apply("1234", NumberProcessing.TRIPLETS))
		assertEquals("1 2 3 4 5 6", NumberProcessing.apply("123456", NumberProcessing.TRIPLETS))
	}

	@Test
	fun quadrupletsKeepFourAndSpellLonger() {
		assertEquals("1234", NumberProcessing.apply("1234", NumberProcessing.QUADRUPLETS))
		assertEquals("1 2 3 4 5", NumberProcessing.apply("12345", NumberProcessing.QUADRUPLETS))
		assertEquals("1 2 3 4 5 6 7 8", NumberProcessing.apply("12345678", NumberProcessing.QUADRUPLETS))
	}

	@Test
	fun decimalsTimesAndDatesAreLeftAlone() {
		for (text in listOf("3.14", "2:30", "2:30:15", "2026-09-07", "12/05", "1st")) {
			assertEquals(text, NumberProcessing.apply(text, NumberProcessing.DIGITS))
			assertEquals(text, NumberProcessing.apply(text, NumberProcessing.TRIPLETS))
		}
	}

	@Test
	fun onlyClockTimesKeepTheirColons() {
		assertEquals("12:3 0 0 0 0 0 0", NumberProcessing.apply("12:3000000", NumberProcessing.PAIRS))
		assertEquals("1:2 3 0", NumberProcessing.apply("1:230", NumberProcessing.PAIRS))
		assertEquals("Call 95:2 6 6.", NumberProcessing.apply("Call 95:266.", NumberProcessing.PAIRS))
		assertEquals("Meet at 2:30.", NumberProcessing.apply("Meet at 2:30.", NumberProcessing.DIGITS))
	}

	@Test
	fun thousandsGroupingsFollowTheMode() {
		assertEquals("1024", NumberProcessing.apply("1,024", NumberProcessing.QUADRUPLETS))
		assertEquals("1 0 2 4", NumberProcessing.apply("1,024", NumberProcessing.PAIRS))
		assertEquals("1 0 2 4", NumberProcessing.apply("1,024", NumberProcessing.DIGITS))
		assertEquals(
			"9 5 2 6 6 2 6 1",
			NumberProcessing.apply("95,266,261", NumberProcessing.QUADRUPLETS)
		)
		assertEquals(
			"9 5 2 6 6 2 6 1",
			NumberProcessing.apply("95,266,261", NumberProcessing.DIGITS)
		)
	}

	@Test
	fun edgePunctuationDoesNotShieldARun() {
		assertEquals(
			"Call 9 5 2 6 6 2 6 1.",
			NumberProcessing.apply("Call 95266261.", NumberProcessing.QUADRUPLETS)
		)
		assertEquals(
			"Room 101.",
			NumberProcessing.apply("Room 101.", NumberProcessing.QUADRUPLETS)
		)
		assertEquals(
			"Hello, 12.",
			NumberProcessing.apply("Hello, 12.", NumberProcessing.PAIRS)
		)
		assertEquals(
			"1 2 3,",
			NumberProcessing.apply("123,", NumberProcessing.PAIRS)
		)
	}

	@Test
	fun letterGluedTokensAreLeftAlone() {
		assertEquals("teamtalk5", NumberProcessing.apply("teamtalk5", NumberProcessing.DIGITS))
		assertEquals("abc123", NumberProcessing.apply("abc123", NumberProcessing.PAIRS))
		assertEquals("5G", NumberProcessing.apply("5G", NumberProcessing.DIGITS))
	}

	@Test
	fun ordinaryTextIsUntouched() {
		for (text in listOf("Hello world", "Navigate up", "")) {
			assertEquals(text, NumberProcessing.apply(text, NumberProcessing.DIGITS))
		}
	}

	/**
	 * Why the service regroups before it fixes: the readability rules must
	 * see the digits as the listener asked for them.
	 */
	@Test
	fun numbersRunBeforeFixes() {
		assertEquals(
			"abc 123",
			TextFixes.apply(NumberProcessing.apply("abc123", NumberProcessing.DIGITS))
		)
		assertEquals(
			"Call 1 2 3 4 5",
			TextFixes.apply(NumberProcessing.apply("Call 12345", NumberProcessing.DIGITS))
		)
	}
}
