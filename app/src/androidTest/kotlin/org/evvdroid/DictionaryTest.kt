package org.evvdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The speech service publishes one immutable three-volume snapshot through
 * the engine's native dictionary handle. These tests cover content identity,
 * file leases, all-or-nothing native replacement, and the replacement barrier.
 */
@RunWith(AndroidJUnit4::class)
class DictionaryTest {

    @Test
    fun contentHashDetectsSameSizeSameTimestampReplacement() {
        val source = write("DOJ\tdee oh jay\n")
        val fixedTime = 1_700_000_000_000L
        assertTrue(source.setLastModified(fixedTime))
        val root = File(dir(), "snapshots-hash")
        val first = DictionarySnapshot.prepare(root, LANGUAGE, mapOf(Eci.DICT_MAIN to source), null)
        val firstIdentity = first.identity
        val firstPath = File(first.paths[Eci.DICT_MAIN]!!)

        source.writeText("DOJ\tbee oh jay\n", Charsets.ISO_8859_1)
        assertEquals("replacement fixture must keep its byte length", 15L, source.length())
        assertTrue(source.setLastModified(fixedTime))
        val second = DictionarySnapshot.prepare(root, LANGUAGE, mapOf(Eci.DICT_MAIN to source), first)
        try {
            assertNotEquals("same size and timestamp hid changed contents", firstIdentity, second.identity)
            assertTrue("first immutable snapshot disappeared while still owned", firstPath.exists())
            assertTrue("new snapshot was not published", File(second.paths[Eci.DICT_MAIN]!!).exists())
        } finally {
            first.retire()
            second.retire()
        }
    }

    @Test
    fun snapshotDirectoryLivesUntilItsLastLeaseCloses() {
        val source = write("DOJ\tdee oh jay\n")
        val snapshot = DictionarySnapshot.prepare(
            File(dir(), "snapshots-lease"),
            LANGUAGE,
            mapOf(Eci.DICT_MAIN to source),
            null
        )
        val volume = File(snapshot.paths[Eci.DICT_MAIN]!!)
        val directory = volume.parentFile!!
        val lease = snapshot.acquire()
        snapshot.retire()
        assertTrue("retirement deleted a leased snapshot", directory.exists())
        lease.close()
        assertFalse("last lease did not release the snapshot directory", directory.exists())
    }

    @Test
    fun allThreeVolumesAreLoadedAsOneNativeSet() {
        val e = open()
        val root = File(dir(), "three-volumes")
        val main = write("walk\tthis is the special main pronunciation\n")
        val rootFile = write("walking\twalk\n")
        val abbr = write("LOL\tthis is the special abbreviation pronunciation\n")
        val beforeMain = samples(e, "walk")
        val beforeRoot = samples(e, "walking")
        val beforeAbbr = samples(e, "LOL")
        val snapshot = DictionarySnapshot.prepare(
            root, LANGUAGE,
            mapOf(
                Eci.DICT_MAIN to main,
                Eci.DICT_ROOT to rootFile,
                Eci.DICT_ABBREVIATION to abbr
            ),
            null
        )
        try {
            assertTrue("native engine rejected the complete three-volume set", e.replaceDictionaries(snapshot.paths))
            val afterMain = samples(e, "walk")
            val afterRoot = samples(e, "walking")
            val afterAbbr = samples(e, "LOL")
            assertTrue("main baseline speech failed", beforeMain > 0)
            assertTrue("root baseline speech failed", beforeRoot > 0)
            assertTrue("abbreviation baseline speech failed", beforeAbbr > 0)
            assertTrue("main dictionary speech failed", afterMain > 0)
            assertTrue("root dictionary speech failed", afterRoot > 0)
            assertTrue("abbreviation dictionary speech failed", afterAbbr > 0)
            assertNotEquals("main volume was not applied", beforeMain, afterMain)
            assertNotEquals("root volume was not applied", beforeRoot, afterRoot)
            assertNotEquals("abbreviation volume was not applied", beforeAbbr, afterAbbr)
        } finally {
            snapshot.retire()
            e.close()
        }
    }


    @Test
    fun largeRootVolumeLoadsThroughNativeBulkPathAndKeepsItsLastEntry() {
        val e = open()
        val rows = buildString(900_000) {
            for (i in 0 until 20_000) {
                append("rootfixture")
                append(i.toString().padStart(5, '0'))
                append("\tunused pronunciation\n")
            }
            // Keep the pronunciation we check as the final actual entry.
            append("walking\twalk\n")
        }
        val rootFile = write(rows)
        val before = samples(e, "walking")
        val snapshot = DictionarySnapshot.prepare(
            File(dir(), "large-root"),
            LANGUAGE,
            mapOf(Eci.DICT_ROOT to rootFile),
            null
        )
        try {
            val staged = File(snapshot.paths[Eci.DICT_ROOT]!!)
            val bytes = staged.readBytes()
            assertTrue(
                "native text volume must end with a blank-line terminator",
                bytes.size >= 2 && bytes[bytes.lastIndex - 1] == '\n'.code.toByte() &&
                    bytes[bytes.lastIndex] == '\n'.code.toByte()
            )
            assertTrue("native engine rejected the large Root volume", e.replaceDictionaries(snapshot.paths))
            val after = samples(e, "walking")
            assertTrue("baseline Root test speech failed", before > 0)
            assertTrue("Root dictionary speech failed after bulk loading", after > 0)
            assertNotEquals("the final Root entry was dropped or not applied", before, after)
        } finally {
            snapshot.retire()
            e.close()
        }
    }

    @Test
    fun failedVolumeKeepsThePreviouslyPublishedSet() {
        val e = open()
        val valid = write("DOJ\tthis is the stable active pronunciation\n")
        val snapshot = DictionarySnapshot.prepare(
            File(dir(), "atomic-old"),
            LANGUAGE,
            mapOf(Eci.DICT_MAIN to valid),
            null
        )
        try {
            val original = samples(e, "DOJ")
            assertTrue("baseline speech failed", original > 0)
            assertTrue(e.replaceDictionaries(snapshot.paths))
            val before = samples(e, "DOJ")
            assertTrue("published dictionary speech failed", before > 0)
            assertNotEquals("initial dictionary set was not applied", original, before)
            val bad = arrayOf(valid.absolutePath, File(dir(), "missing-root.dic").absolutePath, null)
            assertFalse("invalid root volume was published", e.replaceDictionaries(bad))
            assertEquals("failed replacement disturbed the active main volume", before, samples(e, "DOJ"))
        } finally {
            snapshot.retire()
            e.close()
        }
    }

    @Test
    fun nativeReplacementWaitsForTheUtteranceLease() {
        val e = open()
        val source = write("DOJ\tdee oh jay\n")
        val snapshot = DictionarySnapshot.prepare(
            File(dir(), "lease-barrier"),
            LANGUAGE,
            mapOf(Eci.DICT_MAIN to source),
            null
        )
        val lease = e.acquireDictionaryLease()
        assertNotNull("native dictionary lease was refused", lease)
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val succeeded = AtomicBoolean(false)
        val thread = Thread({
            started.countDown()
            succeeded.set(e.replaceDictionaries(snapshot.paths))
            finished.countDown()
        }, "dictionary-replacement-test")
        try {
            thread.start()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertFalse("replacement passed an outstanding utterance lease", finished.await(200, TimeUnit.MILLISECONDS))
            lease!!.close()
            assertTrue("replacement did not resume after lease release", finished.await(5, TimeUnit.SECONDS))
            assertTrue("native replacement failed after lease release", succeeded.get())
        } finally {
            if (finished.count == 1L) lease?.close()
            snapshot.retire()
            e.close()
        }
    }

    @Test
    fun textAndBinaryFilesAreDistinguishedForSnapshotPreparation() {
        assertFalse(Dictionaries.isBinary(write("DOJ\tdee oh jay\n")))
        assertTrue(Dictionaries.isBinary(writeBytes(byteArrayOf(0x41, 0x09, 0x42, 0x00, 0x43))))
    }

    @Test
    fun sharedNamesRouteToTheirVolumesAndLanguages() {
        assertEquals(Eci.DICT_MAIN, Dictionaries.volumeForFile("enumain.dic"))
        assertEquals(Eci.DICT_ROOT, Dictionaries.volumeForFile("enuroot.dic"))
        assertEquals(Eci.DICT_ABBREVIATION, Dictionaries.volumeForFile("enuabbr.dic"))
        assertEquals(Eci.DICT_MAIN, Dictionaries.volumeForFile("ESPMAIN.DIC"))
        assertEquals(Eci.DICT_ABBREVIATION, Dictionaries.volumeForFile("esmabbrev.dic"))
        assertEquals("American English", Dictionaries.languageForFile("enumain.dic"))
        assertEquals("British English", Dictionaries.languageForFile("engroot.dic"))
    }

    private fun open(): EvvEngine {
        val engine = EvvEngine.open(LANGUAGE)
        assertNotNull("no engine", engine)
        val target = engine!!
        target.setSampleRate(11025)
        target.setAbbreviations(true)
        target.applyVoice(0)
        return target
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

    private fun write(text: String): File {
        val file = File(dir(), "dict-${System.nanoTime()}.dic")
        file.writeBytes(text.toByteArray(Charsets.ISO_8859_1))
        return file
    }

    private fun writeBytes(bytes: ByteArray): File {
        val file = File(dir(), "binary-${System.nanoTime()}.dic")
        file.writeBytes(bytes)
        return file
    }

    private fun dir(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.cacheDir, "dictionary-tests").apply { mkdirs() }
    }

    private companion object {
        const val LANGUAGE = 0x00010000
    }
}
