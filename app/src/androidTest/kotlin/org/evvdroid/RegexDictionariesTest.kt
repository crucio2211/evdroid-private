package org.evvdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The user dictionary written as regular expressions.
 *
 * What is checked here is the reading of the file and the rewriting, both
 * off the page: a pattern that fires where it should and nowhere else is
 * decided without an engine. The sample is written here rather than shipped
 * from anywhere.
 */
@RunWith(AndroidJUnit4::class)
class RegexDictionariesTest {

	@Test
	fun patternsRewriteInFileOrder() {
		val loaded = load("NVDA\ten vee dee ay\n")
		assertEquals("say en vee dee ay", RegexDictionaries.apply(loaded, "say NVDA"))
	}

	@Test
	fun groupsCarryOver() {
		val loaded = load("\\b(\\d+)kg\t$1 kilograms\n")
		assertEquals("5 kilograms", RegexDictionaries.apply(loaded, "5kg"))
		assertEquals("flour", RegexDictionaries.apply(loaded, "flour"))
	}

	@Test
	fun laterEntriesSeeEarlierResults() {
		val loaded = load("NVDA\ten vee dee ay\nvee\tV\n")
		assertEquals("say en V dee ay", RegexDictionaries.apply(loaded, "say NVDA"))
	}

	@Test
	fun commentsBlanksAndBadLinesAreSkipped() {
		val file = write(
			"# a comment\n" +
				"\n" +
				"   \n" +
				"no tab here\n" +
				"\tleading tab\n" +
				"trailing tab\t\n" +
				"NVDA\ten vee dee ay\n"
		)
		assertEquals("one usable pattern", 1, RegexDictionaries.count(file))
		val loaded = RegexDictionaries.load(file)
		assertNotNull(loaded)
		assertEquals("say en vee dee ay", RegexDictionaries.apply(loaded, "say NVDA"))
	}

	@Test
	fun aBadPatternDoesNotSinkTheFile() {
		val loaded = load("([a-z\tbroken\nNVDA\ten vee dee ay\n")
		assertNotNull("the good line went down with the bad one", loaded)
		assertEquals(1, loaded!!.entries.size)
		assertEquals("say en vee dee ay", RegexDictionaries.apply(loaded, "say NVDA"))
	}

	@Test
	fun anEmptyFileLoadsAsNothing() {
		val file = write("# only a comment\n\n")
		assertEquals(0, RegexDictionaries.count(file))
		assertNull(RegexDictionaries.load(file))
	}

	@Test
	fun aMissingFileLoadsAsNothing() {
		val missing = File(dir(), "not-there.dic")
		if (missing.exists()) missing.delete()
		assertEquals(0, RegexDictionaries.count(missing))
		assertNull(RegexDictionaries.load(missing))
	}

	@Test
	fun nothingLoadedLeavesTextAlone() {
		assertEquals("say NVDA", RegexDictionaries.apply(null, "say NVDA"))
		assertEquals("", RegexDictionaries.apply(null, ""))
	}

	@Test
	fun emptyTextStaysEmpty() {
		val loaded = load("NVDA\ten vee dee ay\n")
		assertEquals("", RegexDictionaries.apply(loaded, ""))
	}

	@Test
	fun aFreshLoadIsCurrentAndADeletedFileIsNot() {
		val file = write("NVDA\ten vee dee ay\n")
		val loaded = RegexDictionaries.load(file)
		assertNotNull(loaded)
		assertTrue(RegexDictionaries.isCurrent(loaded))
		assertFalse(RegexDictionaries.isCurrent(null))
		file.delete()
		assertFalse("a deleted file still counts as current", RegexDictionaries.isCurrent(loaded))
	}

	@Test
	fun inlineFlagsAreHonoured() {
		val loaded = load("(?i)\\bnvda\ten vee dee ay\n")
		assertEquals("say en vee dee ay", RegexDictionaries.apply(loaded, "say nvda"))
	}

	private fun load(text: String): RegexDictionaries.Loaded? {
		val loaded = RegexDictionaries.load(write(text))
		assertNotNull("nothing usable in: $text", loaded)
		return loaded
	}

	private fun dir(): File {
		val to = InstrumentationRegistry.getInstrumentation().targetContext
		return File(to.cacheDir, "regex-tests").apply { mkdirs() }
	}

	private var fileNumber = 0

	private fun write(text: String): File {
		val file = File(dir(), "test-${fileNumber++}.dic")
		file.writeBytes(text.toByteArray(Charsets.UTF_8))
		return file
	}
}
