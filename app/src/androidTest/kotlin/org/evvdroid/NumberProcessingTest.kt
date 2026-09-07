package org.evvdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Numbers read the way the listener asks.
 *
 * Each check regroups a standalone run and says what must come out, and the
 * untouched checks say what is somebody else's business: decimals, grouped
 * thousands, times, dates and letter-glued tokens are not numbers standing
 * alone.
 */
@RunWith(AndroidJUnit4::class)
class NumberProcessingTest {

	@Test
	fun digitsComeApart() {
		assertEquals("Call 1 2 3 4 5", NumberProcessing.apply("Call 12345", NumberProcessing.DIGITS))
		assertEquals("Room 1 0 1", NumberProcessing.apply("Room 101", NumberProcessing.DIGITS))
		assertEquals("7", NumberProcessing.apply("7", NumberProcessing.DIGITS))
	}

	@Test
	fun pairsGroupLeftToRight() {
		assertEquals("12 34 56", NumberProcessing.apply("123456", NumberProcessing.PAIRS))
		assertEquals("12 34 5", NumberProcessing.apply("12345", NumberProcessing.PAIRS))
		assertEquals("12", NumberProcessing.apply("12", NumberProcessing.PAIRS))
	}

	@Test
	fun tripletsGroupLeftToRight() {
		assertEquals("123 456 7", NumberProcessing.apply("1234567", NumberProcessing.TRIPLETS))
		assertEquals("123 456", NumberProcessing.apply("123456", NumberProcessing.TRIPLETS))
	}

	@Test
	fun quadrupletsGroupLeftToRight() {
		assertEquals("1234 5678", NumberProcessing.apply("12345678", NumberProcessing.QUADRUPLETS))
	}

	@Test
	fun decimalsThousandsTimesAndDatesAreLeftAlone() {
		for (text in listOf("3.14", "1,024", "2:30", "2026-09-07", "12/05", "1st")) {
			assertEquals(text, NumberProcessing.apply(text, NumberProcessing.DIGITS))
			assertEquals(text, NumberProcessing.apply(text, NumberProcessing.TRIPLETS))
		}
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
