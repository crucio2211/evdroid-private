package org.evvdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Words wrapped as phoneme annotations: whole-word, case-blind, once each,
 * leaving annotations already present alone.
 */
@RunWith(AndroidJUnit4::class)
class PhonemeDictionariesTest {

	private val map = mapOf("produce" to ".1pro.0dus", "read" to ".1rid")

	@Test
	fun listedWordIsWrapped() {
		assertEquals(
			"the `[.1pro.0dus] is fresh",
			PhonemeDictionaries.apply("the produce is fresh", map)
		)
	}

	@Test
	fun matchingIgnoresCaseAndKeepsTheRest() {
		assertEquals(
			"`[.1rid] it, READ it again!",
			PhonemeDictionaries.apply("Read it, READ it again!", mapOf("read" to ".1rid"))
		)
	}

	@Test
	fun partialWordsAreLeftAlone() {
		assertEquals(
			"reproduce the reproduction",
			PhonemeDictionaries.apply("reproduce the reproduction", map)
		)
	}

	@Test
	fun punctuationStillBoundsAWord() {
		assertEquals(
			"says `[.1pro.0dus], then stops.",
			PhonemeDictionaries.apply("says produce, then stops.", map)
		)
	}

	@Test
	fun presentAnnotationsAreNotWrappedTwice() {
		val text = "the `[.0prx.1dyus] is fresh"
		assertEquals(text, PhonemeDictionaries.apply(text, mapOf("pro" to ".1pro")))
		assertEquals(text, PhonemeDictionaries.apply(text, map))
	}

	@Test
	fun emptyMapOrUnknownWordsPassThrough() {
		assertEquals("the produce is fresh", PhonemeDictionaries.apply("the produce is fresh", emptyMap()))
		assertEquals("nothing listed here", PhonemeDictionaries.apply("nothing listed here", map))
		assertEquals("", PhonemeDictionaries.apply("", map))
	}

	@Test
	fun loadSkipsCommentsBlanksAndBrokenEntries() {
		val file = File.createTempFile("phonemes", ".dic").apply {
			writeText("# words and their phones\nproduce\t.1pro.0dus\n\nbroken line\nread\t.1rid\nPRODUCE\t.0prx.1dyus\n")
			deleteOnExit()
		}
		assertEquals(mapOf("produce" to ".1pro.0dus", "read" to ".1rid"), PhonemeDictionaries.load(file))
		assertEquals(2, PhonemeDictionaries.count(file))
	}

	@Test
	fun loadRefusesEntriesThatWouldBreakAnAnnotation() {
		val file = File.createTempFile("phonemes", ".dic").apply {
			writeText("bad\ta b\nworse\ta`b\nread\t.1rid\n")
			deleteOnExit()
		}
		assertEquals(mapOf("read" to ".1rid"), PhonemeDictionaries.load(file))
	}
}
