package org.evvdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DictionaryTest {
    @Test
    fun externalTextSnapshotLoadsAllEntriesInOneNativeSwap() {
        val engine = open()
        try {
            val source = write("DOJ\tdee oe jeigh\nDOS\t\u0060[das]\ncafé\t\u0060[kaf1e]\n")
            val nativeFile = File(dir(), "main.dic").apply {
                writeBytes(DictionaryData.encodeSource(source, engine.language))
            }
            assertTrue(engine.replaceDictionaries(mapOf(Eci.DICT_MAIN to nativeFile.absolutePath)))
            assertEquals("dee oe jeigh", engine.lookUpWord(Eci.DICT_MAIN, "DOJ"))
            assertEquals("\u0060[das]", engine.lookUpWord(Eci.DICT_MAIN, "DOS"))
            assertNotNull(engine.lookUpWord(Eci.DICT_MAIN, "café"))
            assertTrue("the phoneme pronunciation was not used", samples(engine, "DOS") > 0)
        } finally {
            engine.close()
        }
    }

    @Test
    fun replacingDictionarySetDoesNotKeepOldEntries() {
        val engine = open()
        try {
            val first = File(dir(), "first.dic").apply {
                writeBytes(DictionaryData.encodeSource(write("DOJ\tfirst reading\n"), engine.language))
            }
            val second = File(dir(), "second.dic").apply {
                writeBytes(DictionaryData.encodeSource(write("DOS\tsecond reading\n"), engine.language))
            }
            assertTrue(engine.replaceDictionaries(mapOf(Eci.DICT_MAIN to first.absolutePath)))
            assertEquals("first reading", engine.lookUpWord(Eci.DICT_MAIN, "DOJ"))
            assertTrue(engine.replaceDictionaries(mapOf(Eci.DICT_MAIN to second.absolutePath)))
            assertEquals(null, engine.lookUpWord(Eci.DICT_MAIN, "DOJ"))
            assertEquals("second reading", engine.lookUpWord(Eci.DICT_MAIN, "DOS"))
        } finally {
            engine.close()
        }
    }

    @Test
    fun emptyReplacementRestoresBuiltInDictionarySet() {
        val engine = open()
        try {
            val custom = File(dir(), "custom.dic").apply {
                writeBytes(DictionaryData.encodeSource(write("DOJ\tcustom reading\n"), engine.language))
            }
            assertTrue(engine.replaceDictionaries(mapOf(Eci.DICT_MAIN to custom.absolutePath)))
            assertNotNull(engine.lookUpWord(Eci.DICT_MAIN, "DOJ"))
            assertTrue(engine.replaceDictionaries(emptyMap()))
            assertEquals(null, engine.lookUpWord(Eci.DICT_MAIN, "DOJ"))
        } finally {
            engine.close()
        }
    }

    @Test
    fun snapshotIdentityIncludesVolumeNamesAndBytes() {
        val main = mapOf(Eci.DICT_MAIN to "DOJ\treading\n".toByteArray(Charsets.ISO_8859_1))
        val changedBytes = mapOf(Eci.DICT_MAIN to "DOJ\tphoneme\n".toByteArray(Charsets.ISO_8859_1))
        val changedVolume = mapOf(Eci.DICT_ROOT to main.getValue(Eci.DICT_MAIN))
        assertNotEquals(DictionaryData.snapshotHash(main), DictionaryData.snapshotHash(changedBytes))
        assertNotEquals(DictionaryData.snapshotHash(main), DictionaryData.snapshotHash(changedVolume))
    }

    @Test
    fun binaryDictionarySourceIsCopiedWithoutTextRewriting() {
        val source = writeBytes(byteArrayOf(0x41, 0x09, 0x42, 0x00, 0x43))
        assertTrue(Dictionaries.isBinary(source))
        assertTrue(DictionaryData.encodeSource(source, 0x00010000).contentEquals(source.readBytes()))
    }

    private fun open(): EvvEngine {
        val engine = EvvEngine.open(0x00010000)
        assertNotNull("no US English engine", engine)
        engine!!.setSampleRate(11025)
        engine.applyVoice(0)
        return engine
    }

    private fun dir(): File {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        return File(target.cacheDir, "dictionary-snapshots-test").apply { mkdirs() }
    }

    private var fileNumber = 0
    private fun write(text: String): File = writeBytes(text.toByteArray(Charsets.ISO_8859_1))
    private fun writeBytes(bytes: ByteArray): File {
        val file = File(dir(), "source-${fileNumber++}.dic")
        file.writeBytes(bytes)
        return file
    }

    private fun samples(engine: EvvEngine, text: String): Int {
        if (!engine.speak(text)) return -1
        var total = 0
        val buffer = ByteArray(4096)
        while (true) {
            val count = engine.read(buffer)
            if (count <= 0) break
            total += count
        }
        return total
    }
}
