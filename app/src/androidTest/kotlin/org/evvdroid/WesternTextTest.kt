package org.evvdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android text turned into what the engine reads.
 *
 * Bytes for the Western languages, UTF-16 units for Japanese: what comes
 * out of each encoder must be exactly what the engine's own code set holds,
 * with nothing in it that would end the text early.
 */
@RunWith(AndroidJUnit4::class)
class WesternTextTest {

	@Test
	fun asciiPassesThroughWide() {
		assertArrayEquals("Hello".toCharArray(), WesternText.encodeWide("Hello"))
	}

	@Test
	fun kanaPassThroughWide() {
		assertArrayEquals("こんにちは".toCharArray(), WesternText.encodeWide("こんにちは"))
	}

	@Test
	fun wideKeepsTypographicEquivalents() {
		assertArrayEquals("...".toCharArray(), WesternText.encodeWide("…"))
		assertArrayEquals(" euro ".toCharArray(), WesternText.encodeWide("€"))
	}

	@Test
	fun wideNeutralisesNoughtsAndLoneSurrogates() {
		assertArrayEquals("a b".toCharArray(), WesternText.encodeWide("a\u0000b"))
		assertArrayEquals("a ".toCharArray(), WesternText.encodeWide("a\uD83D"))
	}

	@Test
	fun wideKeepsPairsTogether() {
		assertArrayEquals("a\uD83D\uDE00b".toCharArray(), WesternText.encodeWide("a\uD83D\uDE00b"))
	}

	@Test
	fun bytesKeepWesternCharacters() {
		assertEquals(1, WesternText.encode("é").size)
		assertArrayEquals(
			" euro ".toByteArray(Charsets.ISO_8859_1),
			WesternText.encode("€")
		)
	}
}
