package org.evvdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `@` is always the word `at`; dots and pluses go quiet only with
 * punctuation off, where the engine would otherwise spell them.
 */
@RunWith(AndroidJUnit4::class)
class SymbolWordsTest {

	@Test
	fun atSignBecomesAtWithSpaces() {
		assertEquals("user at example.com", SymbolWords.apply("user@example.com", false))
		assertEquals("user at example.com", SymbolWords.apply("user@example.com", true))
	}

	@Test
	fun dotsBetweenLowercaseGoQuietWhenOff() {
		assertEquals(
			"user at example com",
			SymbolWords.apply("user@example.com", true)
		)
	}

	@Test
	fun sentenceDotsDecimalsAndInitialsStay() {
		assertEquals("Hello. World", SymbolWords.apply("Hello. World", true))
		assertEquals("Call 3.14 now", SymbolWords.apply("Call 3.14 now", true))
		assertEquals("U.S. laws", SymbolWords.apply("U.S. laws", true))
		assertEquals("He said Hi.", SymbolWords.apply("He said Hi.", true))
	}

	@Test
	fun plusGoesQuietWhenOffButSpokenWhenOn() {
		assertEquals("a b", SymbolWords.apply("a+b", true))
		assertEquals("a+b", SymbolWords.apply("a+b", false))
	}

	@Test
	fun dotsStayWhenPunctuationIsOn() {
		assertEquals("example.com", SymbolWords.apply("example.com", false))
	}

	@Test
	fun emptyPassesThrough() {
		assertEquals("", SymbolWords.apply("", true))
	}
}
