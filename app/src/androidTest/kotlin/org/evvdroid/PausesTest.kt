package org.evvdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The three Apple-compatible native ECI pause modes. */
@RunWith(AndroidJUnit4::class)
class PausesTest {

    @Test
    fun modeValuesMatchTheNativeEciParameter() {
        assertEquals("OpenEVV public eciSetParam pause-mode parameter", 33, Eci.PARAM_PAUSES)
        assertEquals(Eci.PAUSES_NONE, Pauses.KEEP)
        assertEquals(Eci.PAUSES_AT_END, Pauses.END_ONLY)
        assertEquals(Eci.PAUSES_ALWAYS, Pauses.ALL)
        assertEquals(1, Eci.PAUSES_DEFAULT)
    }

    @Test
    fun allThreeModesAreAcceptedByTheEngine() {
        val engine = EvvEngine.open(ENGLISH)
        assertNotNull(engine)
        val target = engine!!
        try {
            target.setPauseMode(Pauses.KEEP)
            assertEquals(Pauses.KEEP, target.pauseMode)
            target.setPauseMode(Pauses.END_ONLY)
            assertEquals(Pauses.END_ONLY, target.pauseMode)
            target.setPauseMode(Pauses.ALL)
            assertEquals(Pauses.ALL, target.pauseMode)
        } finally {
            target.close()
        }
    }

    @Test
    fun nativeAlwaysModeShortensMoreThanEndOnlyAndNever() {
        val engine = EvvEngine.open(ENGLISH)
        assertNotNull(engine)
        val target = engine!!
        try {
            target.setSampleRate(11025)
            target.applyVoice(0)
            target.setPauseMode(Pauses.KEEP)
            val never = say(target, SENTENCE)
            target.setPauseMode(Pauses.END_ONLY)
            val endOnly = say(target, SENTENCE)
            target.setPauseMode(Pauses.ALL)
            val always = say(target, SENTENCE)
            android.util.Log.e("evvpause", "never=$never endOnly=$endOnly always=$always")
            assertTrue("no speech was generated", never > 0)
            assertTrue("end-only mode did not shorten the final pause", endOnly < never)
            assertTrue("always mode did not shorten punctuation pauses", always < endOnly)
            assertTrue("too much speech disappeared", always > never / 2)
        } finally {
            target.close()
        }
    }

    private fun say(engine: EvvEngine, text: String): Int {
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

    private companion object {
        const val ENGLISH = 0x00010000
        const val SENTENCE = "One, two, three. Four, five, six. Seven, eight."
    }
}
