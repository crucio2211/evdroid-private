package org.evvdroid

import android.content.Context
import android.util.Log
import com.xw.vvtts.core.VvttsCore
import com.xw.vvtts.utils.KonaVoice
import java.io.File
import java.io.FileWriter
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Apple-Eloquence backend for evvdroid (personal-build / full-swap mode).
 *
 * Uses the prebuilt Apple-derived engine (libeci.so + language .so +
 * libvvtts_core.so from GGbond2007/eloquence-tts-android) instead of openevv.
 * The .so files are NOT committed; native/fetch-apple-libs.sh downloads them
 * at build time. See APPLE_ENGINE_NOTES.md.
 *
 * Public API mirrors EvvEngine so EvvTtsService / EvvPreview keep working:
 *  - speak(text) blocks, synthesizes to PCM, then read() drains it.
 *  - Sample rate is fixed at 11025 Hz (Apple engine only supports 8k/11.025k).
 *  - Dictionaries are no-ops: vvtts_core exposes no eci dict APIs. The Kotlin
 *    text pipeline (regex, emoji, numbers) still runs in the service.
 *
 * Voice mapping (evvdroid preset 0-7 -> Apple preset 1-8):
 *  0 Reed -> 1 Reed, 1 Shelley -> 2 Shelley, 2 Bobby -> 8 Eddy (boy-ish),
 *  3 Rocko -> 4 Rocko, 4 Glen -> 5 Flo (* no exact equivalent, gender flips *),
 *  5 Sandy -> 3 Sandy, 6 Grandma -> 6 Grandma, 7 Grandpa -> 7 Grandpa.
 */
class AppleEngine private constructor(val language: Int) {

  private val guard = Any()

  @Volatile
  private var closed = false

  var sampleRateHz: Int = SAMPLE_RATE
    private set

  private var handle: Long = 0L
  private var pcmBytes: ByteArray = ByteArray(0)
  private var readPos: Int = 0

  @Volatile
  private var aborted = false

  private var baseSpeed = 50
  private var basePitch = 65
  private var wantSpeed = 50
  private var wantPitch = 65
  private var currentApplePreset = 1
  private var currentShape: Map<Int, Int> = emptyMap()

  companion object {
    private const val TAG = "evvdroid-apple"
    const val SAMPLE_RATE = 11025

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
      appContext = context.applicationContext
    }

    /** The 14 Apple dialects. Matches Eci.localeOf keys. */
    val availableDialects: IntArray = intArrayOf(
      0x10000, 0x10001, // en-US, en-GB
      0x20000, 0x20001, // es-ES, es-MX
      0x30000, 0x30001, // fr-FR, fr-CA
      0x40000, // de-DE
      0x50000, // it-IT
      0x60000, 0x60001, // zh-CN, zh-TW
      0x70000, // pt-BR
      0x80000, // ja-JP
      0x90000, // fi-FI
      0xA0000 // ko-KR
    )

    const val VERSION = "apple-eloquence-elf (tvOS 18.2)"

    fun open(language: Int): AppleEngine? {
      val ctx = appContext
      if (ctx == null) {
        Log.e(TAG, "AppleEngine.init(context) was never called")
        return null
      }
      val e = AppleEngine(language)
      if (!e.ensureHandle(ctx)) return null
      return e
    }

    /** evvdroid preset 0-7 -> Apple preset 1-8. See class doc for mismatches. */
    fun applePresetFor(evvPreset: Int): Int = when (evvPreset.coerceIn(0, 7)) {
      0 -> 1 // Reed -> Reed
      1 -> 2 // Shelley -> Shelley
      2 -> 8 // Bobby -> Eddy
      3 -> 4 // Rocko -> Rocko
      4 -> 5 // Glen -> Flo (no exact equivalent)
      5 -> 3 // Sandy -> Sandy
      6 -> 6 // Grandma -> Grandma
      else -> 7 // Grandpa -> Grandpa
    }
  }

  private fun buildEloquenceConfig(libraryDir: File): String {
    val sb = StringBuilder()
    val langs = arrayOf(
      arrayOf("1.0", "libenu.so", null),
      arrayOf("1.1", "libeng.so", null),
      arrayOf("2.0", "libesp.so", null),
      arrayOf("2.1", "libesm.so", null),
      arrayOf("3.0", "libfra.so", null),
      arrayOf("3.1", "libfrc.so", null),
      arrayOf("4.0", "libdeu.so", null),
      arrayOf("5.0", "libita.so", null),
      arrayOf("6.0", "libchs.so", "libchsrom.so"),
      arrayOf("6.1", "libcht.so", "libchtrom.so"),
      arrayOf("7.0", "libptb.so", null),
      arrayOf("8.0", "libjpn.so", "libjpnrom.so"),
      arrayOf("9.0", "libfin.so", null),
      arrayOf("10.0", "libkor.so", "libkorrom.so")
    )
    for (l in langs) {
      sb.append("[").append(l[0]).append("]\nPath=")
      sb.append(File(libraryDir, l[1] as String).absolutePath).append("\n")
      if (l[2] != null) {
        sb.append("Path_Rom=")
        sb.append(File(libraryDir, l[2] as String).absolutePath).append("\n")
      }
      sb.append("Version=6.1\n\n")
    }
    sb.append("Voice1=0 50 65 30 0 0 50 92\n")
    sb.append("Voice2=0 50 81 50 0 0 50 95\n")
    sb.append("Voice3=0 50 93 50 0 0 22 95\n")
    sb.append("Voice4=0 50 56 0 0 0 86 93\n")
    sb.append("Voice5=0 50 65 30 0 0 50 92\n")
    sb.append("Voice6=0 50 89 40 0 0 56 95\n")
    sb.append("Voice7=0 50 68 40 3 0 45 90\n")
    sb.append("Voice8=0 50 61 20 18 0 30 90\n")
    sb.append("Voice9=0 50 69 0 0 0 50 92\n")
    return sb.toString()
  }

  private fun ensureHandle(ctx: Context): Boolean = synchronized(guard) {
    if (closed) return false
    if (handle != 0L) return true
    return try {
      val info = ctx.applicationInfo
      val libDir = File(info.nativeLibraryDir)
      val cfgDir = File(ctx.filesDir, "eloquence")
      if (!cfgDir.exists()) cfgDir.mkdirs()
      FileWriter(File(cfgDir, "eci.ini")).use { it.write(buildEloquenceConfig(libDir)) }
      val h = VvttsCore.openEngine(cfgDir.absolutePath, libDir.absolutePath, language)
      if (h == 0L) {
        Log.e(TAG, "openEngine failed for 0x%08x".format(language))
        false
      } else {
        handle = h
        true
      }
    } catch (e: Exception) {
      Log.e(TAG, "Apple engine init failed", e)
      false
    }
  }

  private fun charsetFor(dialect: Int): Charset = when (dialect) {
    0x60000 -> Charset.forName("GB18030")
    0x60001 -> Charset.forName("Big5")
    0x80000 -> Charset.forName("Shift_JIS")
    0xA0000 -> Charset.forName("EUC-KR")
    else -> Charset.forName("windows-1252")
  }

  fun setSampleRate(hz: Int) = synchronized(guard) {
    // Apple engine only plays 8000 / 11025; evvdroid UI offers exactly those two.
    // Keep 11025 always: resampling buys nothing and 8000 sounds worse.
    if (hz == 8000) Log.w(TAG, "8000 Hz requested; staying at 11025 Hz (Apple engine)")
    sampleRateHz = SAMPLE_RATE
  }

  fun setAnnotations(@Suppress("UNUSED_PARAMETER") on: Boolean) {
    // Apple engine annotations differ; TextPipeline backquote stripping already ran.
  }

  fun setAbbreviations(@Suppress("UNUSED_PARAMETER") on: Boolean) {
    // No eciDictionary switch on this path; abbreviations stay as written.
  }

  fun presetShape(index: Int): Map<Int, Int> = synchronized(guard) {
    if (closed || handle == 0L) return emptyMap()
    val applePreset = applePresetFor(index)
    val voice = KonaVoice.byPreset(applePreset)
    val out = LinkedHashMap<Int, Int>()
    for (param in 0 until Eci.NUM_VOICE_PARAMS) {
      val live = VvttsCore.getVoiceParam(handle, 0, param)
      out[param] = if (live >= 0) live else voice.param(param)
    }
    return out
  }

  fun applyVoice(index: Int, shape: Map<Int, Int> = emptyMap()): Boolean = synchronized(guard) {
    if (closed || handle == 0L) return false
    val applePreset = applePresetFor(index)
    currentApplePreset = applePreset
    currentShape = shape
    val voice = KonaVoice.byPreset(applePreset)
    for (p in 0 until Eci.NUM_VOICE_PARAMS) {
      if (p == Eci.VOICE_SPEED) continue // speed belongs to the listener; set below
      val v = shape[p] ?: voice.param(p)
      VvttsCore.setVoiceParam(handle, 0, p, v.coerceIn(0, 250))
    }
    val speed = (shape[Eci.VOICE_SPEED] ?: Eci.DEFAULT_SPEED).coerceIn(0, Eci.SPEED_MAX)
    VvttsCore.setVoiceParam(handle, 0, Eci.VOICE_SPEED, speed)
    baseSpeed = VvttsCore.getVoiceParam(handle, 0, Eci.VOICE_SPEED).takeIf { it >= 0 } ?: speed
    basePitch = VvttsCore.getVoiceParam(handle, 0, Eci.VOICE_PITCH_BASELINE).takeIf { it >= 0 }
      ?: voice.pitchBase
    wantSpeed = baseSpeed
    wantPitch = basePitch
    return true
  }

  fun setRatePercent(percent: Int) = synchronized(guard) {
    if (closed) return
    wantSpeed = SpeechRate.speedForPercent(baseSpeed, percent)
  }

  fun setPitchPercent(percent: Int) = synchronized(guard) {
    if (closed) return
    wantPitch = (basePitch.toLong() * percent / 100).toInt().coerceIn(0, 250)
  }

  fun getVoiceParam(param: Int): Int = synchronized(guard) {
    if (closed || handle == 0L) -1 else VvttsCore.getVoiceParam(handle, 0, param)
  }

  fun loadDictionary(@Suppress("UNUSED_PARAMETER") volume: Int, @Suppress("UNUSED_PARAMETER") path: String): Int {
    Log.w(TAG, "volume dictionaries are not supported on the Apple path; keeping regex/text pipeline only")
    return 0
  }

  fun teachWord(@Suppress("UNUSED_PARAMETER") volume: Int, @Suppress("UNUSED_PARAMETER") key: String, @Suppress("UNUSED_PARAMETER") say: String): Int = 0

  fun lookUpWord(@Suppress("UNUSED_PARAMETER") volume: Int, @Suppress("UNUSED_PARAMETER") key: String): String? = null

  fun forgetDictionaries() {
  }

  fun speak(text: String): Boolean = synchronized(guard) {
    if (closed || handle == 0L) return false
    aborted = false
    readPos = 0
    pcmBytes = ByteArray(0)
    // Rate/pitch travel with the utterance (same contract as EvvEngine).
    VvttsCore.setVoiceParam(handle, 0, Eci.VOICE_SPEED, wantSpeed.coerceIn(0, 250))
    VvttsCore.setVoiceParam(handle, 0, Eci.VOICE_PITCH_BASELINE, wantPitch.coerceIn(0, 250))
    // Re-apply user shape over the rate/pitch so sliders win.
    for ((param, value) in currentShape) {
      if (param == Eci.VOICE_SPEED || param == Eci.VOICE_PITCH_BASELINE) continue
      VvttsCore.setVoiceParam(handle, 0, param, value.coerceIn(0, 250))
    }
    val cs = charsetFor(language)
    val encoded: ByteArray = try {
      val bb = cs.newEncoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
        .encode(java.nio.CharBuffer.wrap(text))
      val b = ByteArray(bb.remaining())
      bb.get(b)
      b
    } catch (e: Exception) {
      Log.e(TAG, "encode failed", e)
      return false
    }
    val charsetId = if (language == 0x60000) VvttsCore.CHARSET_GBK else VvttsCore.CHARSET_1252
    val ctx = appContext ?: return false
    val outFile = File(ctx.cacheDir, "core_pcm_out")
    val pcm: ShortArray = VvttsCore.synth(handle, language, encoded, charsetId, outFile.absolutePath)
      ?: return false
    if (aborted) return false
    val bytes = ByteArray(pcm.size * 2)
    for (i in pcm.indices) {
      val v = pcm[i].toInt()
      bytes[i * 2] = (v and 0xFF).toByte()
      bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
    }
    pcmBytes = bytes
    readPos = 0
    return true
  }

  fun read(into: ByteArray): Int {
    if (closed) return -1
    synchronized(guard) {
      if (aborted) return -1
      if (readPos >= pcmBytes.size) return 0
      val n = minOf(into.size, pcmBytes.size - readPos)
      System.arraycopy(pcmBytes, readPos, into, 0, n)
      readPos += n
      return n
    }
  }

  fun stop() = synchronized(guard) {
    aborted = true
    readPos = pcmBytes.size
    if (handle != 0L) VvttsCore.stop(handle) // no-op on Apple path by design
  }

  fun close() = synchronized(guard) {
    if (closed) return
    closed = true
    aborted = true
    if (handle != 0L) {
      VvttsCore.shutdown(handle) // frees session wrapper; never calls eciDelete (would crash)
      handle = 0L
    }
    pcmBytes = ByteArray(0)
  }
}
