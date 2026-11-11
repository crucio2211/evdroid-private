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
        assertEquals("Apple ECI pause-mode parameter", 33, Eci.PARAM_PAUSES)
        assertEquals(Eci.PAUSES_NONE, Pauses.KEEP)
        assertEquals(Eci.PAUSES_AT_END, Pauses.END_ONLY)
        assertEquals(Eci.PAUSES_ALWAYS, Pauses.ALL)
        assertEquals(1, Eci.PAUSES_DEFAULT)
    }

    @Test
    fun allThreeModesAreAcceptedByTheEngine() {
        val engine = EvvEngine.open(ENGLISH)
        assertNotNull(engine)
        try {
            engine!!.setPauseMode(Pauses.KEEP)
            assertEquals(Pauses.KEEP, engine.pauseMode)
            engine.setPauseMode(Pauses.END_ONLY)
            assertEquals(Pauses.END_ONLY, engine.pauseMode)
            engine.setPauseMode(Pauses.ALL)
            assertEquals(Pauses.ALL, engine.pauseMode)
        } finally {
            engine?.close()
        }
    }

    @Test
    fun nativeAlwaysModeShortensMoreThanEndOnlyAndNever() {
        val engine = EvvEngine.open(ENGLISH)
        assertNotNull(engine)
        try {
            engine!!.setSampleRate(11025)
            engine.applyVoice(0)
            engine.setPauseMode(Pauses.KEEP)
            val never = say(engine, SENTENCE)
            engine.setPauseMode(Pauses.END_ONLY)
            val endOnly = say(engine, SENTENCE)
            engine.setPauseMode(Pauses.ALL)
            val always = say(engine, SENTENCE)
            android.util.Log.e("evvpause", "never=$never endOnly=$endOnly always=$always")
            assertTrue("no speech was generated", never > 0)
            assertTrue("end-only mode did not shorten the final pause", endOnly < never)
            assertTrue("always mode did not shorten punctuation pauses", always < endOnly)
            assertTrue("too much speech disappeared", always > never / 2)
        } finally {
            engine?.close()
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
