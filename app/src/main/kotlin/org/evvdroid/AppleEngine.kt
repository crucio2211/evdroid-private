package org.evvdroid

import android.content.Context
import android.util.Log
import com.xw.vvtts.core.VvttsCore
import com.xw.vvtts.utils.KonaVoice
import java.io.File
import java.io.FileWriter
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
 *  - Native synth is 11025 Hz normally; a self-validating probe at session
 *    open tests ECI rate index 2, and when the engine truly renders 22050
 *    (PCM length ~2x), 16 kHz output downsamples genuine content instead
 *    of upsampling. 8 kHz renders natively when accepted. Anything but
 *    proof falls back to 11025, so this can never break normal output.
 *  - Output rate is 8000 / 11025 / 16000 (see setSampleRate). 16000 upsamples
 *    the native PCM and runs the ported 16 kHz presence+air DSP below.
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

  var sampleRateHz: Int = NATIVE_RATE
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

  /** Flattened volume dictionaries (lowercased word to say-text), pushed by
   *  the service when picked files change. Applied as inline word
   *  substitution in speak(): vvtts_core exposes no eci dict APIs, so this
   *  is what makes user dictionaries work on the Apple path. */
  private var substitutions: Map<String, String> = emptyMap()

  /** True when this session proved it renders native 22050 Hz (rate index
   *  2): the probe below synthesizes one calibration word at both rates
   *  and only trusts a ~2x PCM length ratio. Then 16 kHz output downsamples
   *  genuine content instead of upsampling 11 kHz. */
  private var native22k = false

  /** True when eciNewAudioFormat2 accepted a 16000 Hz format on this
   *  session (validated by calibration ratio ~1.45x): pure native 16 kHz
   *  render, no resampling at all. */
  private var native16k = false

  /** Proof that this engine data renders native 16 kHz. Survives session
   *  reopens (property of the voice data, not the session); the live
   *  `native16k` flag tracks only the current session's format state.
   *  (Kept for the on-device indicator; discovery itself is lazy.) */
  private var probed16k = false

  /** Rate discovery already ran on this session (16k format and/or 22k
   *  probe). Speak-time only, so 11k/8k users never pay probe synths. */
  private var rateProbed = false

  /** Human-readable active render path for the on-device indicator. */
  var lastRender = "native 11 kHz"
    private set

  private external fun nativeNewAudioFormat(sessionHandle: Long, rateHz: Int): Int

  /** Replace the dictionary set (called with the flattened word maps). */
  fun setSubstitutions(map: Map<String, String>) = synchronized(guard) {
    if (!closed) substitutions = map
  }

  companion object {
    private const val TAG = "evvdroid-apple"
    const val NATIVE_RATE = 11025
    const val NATIVE_22K = 22050
    const val OUTPUT_16K = 16000
    const val OUTPUT_8K = 8000

    /** Output rates offered on the Apple path (native + resampled). */
    val SUPPORTED_RATES = listOf(OUTPUT_8K, NATIVE_RATE, OUTPUT_16K)

    /**
     * Service-added annotations that must NOT reach the Apple engine.
     * `pp0/`pp1 (openevv phrase prediction) has no Apple equivalent, and
     * `vbN here carries openevv pitch while Apple reads `vb as breathiness,
     * so leftovers would mistune the voice — pitch already travels via
     * setVoiceParam in speak(). Everything else passes through: `vsN speed
     * and `pN pauses match Apple syntax, and user content (phoneme brackets
     * like `[Ekspoz1e], Apple `vN/`vv voice tags) is for the engine to
     * interpret, which is what makes dictionary phonemes work again.
     */
    private val ANNOTATION = Regex("`(pp[01]|vb[0-9]+)")

    /** Whole-word matcher for dictionary substitution (same shape as the
     *  service WORD: letters/digits carrying apostrophes and hyphens). */
    private val WORD = Regex("[A-Za-z0-9]+(?:['-][A-Za-z0-9]+)*")

    /** Sibilant-rich ASCII calibration sentence (all our charsets are
     *  ASCII-safe). Long enough that length ratios are stable, sibilant
     *  enough that true high-rate renders show real energy above 5.5 kHz. */
    private val CAL_TEXT = "She sells six thick socks.".toByteArray(Charsets.US_ASCII)

    /**
     * Fraction of energy above ~5.8 kHz (1-pole highpass). An 11 kHz
     * render holds nothing up there but filter skirt (<0.01); a true
     * high-rate render of sibilants holds clearly more. Second witness
     * beside the length ratio, so a weird-length utterance alone can
     * never validate a rate.
     */
    private fun hfFraction(pcm: ShortArray, rate: Int): Double {
      if (pcm.size < 1000) return 0.0
      val a = Math.exp(-2.0 * PI * 5800.0 / rate.toDouble())
      var y = 0.0
      var eHf = 0.0
      var eTot = 0.0
      for (i in pcm.indices) {
        val x = pcm[i].toDouble()
        y = a * (y + x - (if (i > 0) pcm[i - 1].toDouble() else 0.0))
        eTot += x * x
        if (i >= 100) eHf += y * y
      }
      return if (eTot <= 0.0) 0.0 else (eHf / eTot).coerceIn(0.0, 1.0)
    }

    @Volatile
    private var appContext: Context? = null

    /** Micro-JNI rate bridge (libevvrate.so, apple builds only). Absence
     *  degrades to the upsample path; never fatal. */
    val rateBridgeLoaded: Boolean by lazy {
      runCatching { System.loadLibrary("evvrate"); true }.getOrDefault(false)
    }

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
    fun applePresetFor(evvPreset: Int): Int = when (evvPreset.coerceIn(0, 7)) {      0 -> 1 // Reed -> Reed
      1 -> 2 // Shelley -> Shelley
      2 -> 8 // Bobby -> Eddy
      3 -> 4 // Rocko -> Rocko
      4 -> 5 // Glen -> Flo (no exact equivalent)
      5 -> 3 // Sandy -> Sandy
      6 -> 6 // Grandma -> Grandma
      else -> 7 // Grandpa -> Grandpa
    }

    // ---- Resampling (downsampling only) --------------------------------
    // Pure-native policy: 16 kHz output is proven-native render or honest
    // 11 kHz — never upsampled. Sinc/cubic below exist solely for
    // DOWNsampling genuine content (22k->16k, 16k->11k/8k, 11k->8k).

    /**
     * Bandlimited windowed-sinc resample, mono int16. Bandlimited so no
     * imaging aliases are created in the target band.
     *
     * 32 taps, Hann window, 256 fractional phases precomputed per rate pair
     * and cached; rows normalized to unity DC gain. Cutoff follows the
     * conversion direction (full source band up, anti-alias down).
     */
    private const val SINC_TAPS = 32
    private const val SINC_PHASES = 256
    private val sincTables = HashMap<Pair<Int, Int>, Array<DoubleArray>>()

    private fun sincTable(fromRate: Int, toRate: Int): Array<DoubleArray> {
      synchronized(sincTables) {
        sincTables[fromRate to toRate]?.let { return it }
      }
      val fc = (0.5 * toRate.toDouble() / fromRate.toDouble()).coerceAtMost(0.5)
      val half = SINC_TAPS / 2
      val table = Array(SINC_PHASES) { p ->
        val frac = p.toDouble() / SINC_PHASES.toDouble()
        val row = DoubleArray(SINC_TAPS) { k ->
          val u = (k - half).toDouble() - frac
          val s = if (u == 0.0) 1.0 else sin(PI * fc * u) / (PI * fc * u)
          val w = 0.5 * (1.0 + cos(2.0 * PI * u / SINC_TAPS.toDouble()))
          s * w
        }
        val sum = row.sum()
        if (sum != 0.0) for (k in row.indices) row[k] /= sum
        row
      }
      synchronized(sincTables) { sincTables[fromRate to toRate] = table }
      return table
    }

    /** Cubic (Catmull-Rom) resample, mono int16. Downsampling only. */
    fun resampleCubic(pcm: ShortArray, fromRate: Int, toRate: Int): ShortArray {
      if (fromRate == toRate || pcm.isEmpty()) return pcm
      if (pcm.size == 1) return ShortArray(((1L * toRate) / fromRate).toInt().coerceAtLeast(1)) { pcm[0] }
      val outLen = ((pcm.size.toLong() * toRate) / fromRate).toInt().coerceAtLeast(1)
      val out = ShortArray(outLen)
      fun at(i: Int): Double = pcm[i.coerceIn(0, pcm.size - 1)].toDouble()
      for (j in 0 until outLen) {
        val pos = (j.toLong() * fromRate).toDouble() / toRate.toDouble()
        val i1 = pos.toInt().coerceIn(0, pcm.size - 1)
        val t = (pos - i1).coerceIn(0.0, 1.0)
        val p0 = at(i1 - 1); val p1 = at(i1); val p2 = at(i1 + 1); val p3 = at(i1 + 2)
        val t2 = t * t
        val t3 = t2 * t
        val v = 0.5 * ((2.0 * p1) + (-p0 + p2) * t +
          (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2 +
          (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3)
        out[j] = v.toInt().coerceIn(-32768, 32767).toShort()
      }
      return out
    }

    /** Sinc resample entry point (kept the old name for call sites). */
    fun resampleLinear(pcm: ShortArray, fromRate: Int, toRate: Int): ShortArray {
      if (fromRate == toRate || pcm.isEmpty()) return pcm
      if (pcm.size == 1) return ShortArray(((1L * toRate) / fromRate).toInt().coerceAtLeast(1)) { pcm[0] }
      val table = sincTable(fromRate, toRate)
      val half = SINC_TAPS / 2
      val r = fromRate.toDouble() / toRate.toDouble()
      val outLen = ((pcm.size.toLong() * toRate) / fromRate).toInt().coerceAtLeast(1)
      val out = ShortArray(outLen)
      val n = pcm.size
      for (j in 0 until outLen) {
        val pos = j * r
        val i0 = pos.toInt()
        val phase = (((pos - i0) * SINC_PHASES).toInt()).coerceIn(0, SINC_PHASES - 1)
        val row = table[phase]
        var v = 0.0
        for (k in 0 until SINC_TAPS) {
          val idx = (i0 + k - half).coerceIn(0, n - 1)
          v += pcm[idx].toDouble() * row[k]
        }
        out[j] = v.toInt().coerceIn(-32768, 32767).toShort()
      }
      return out
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
        // Annotation input on: `p pauses, `vs speed and user phoneme/voice
        // tags are interpreted instead of spoken (openevv enables the same
        // PARAM_INPUT_TYPE on its path; Apple defaults it off).
        VvttsCore.setParam(h, Eci.PARAM_INPUT_TYPE, 1)
        // Rate discovery is lazy (first 16 kHz utterance): 11k/8k users
        // never pay probe synths. See speak().
        native16k = false
        native22k = false
        probed16k = false
        rateProbed = false
        true
      }
    } catch (e: Throwable) {
      // Throwable, not Exception: a missing/renamed native (R8) or a
      // failed load arrives as Error, and must degrade to "no engine"
      // rather than killing the caller (settings screen included).
      Log.e(TAG, "Apple engine init failed", e)
      false
    }
  }

  /** Closes the current session and opens a fresh 11 kHz one. Used after
   *  a failed format experiment and when leaving 16 kHz output, so normal
   *  output can never inherit half-applied state. Proof flags survive:
   *  they describe the voice data, not the session. */
  private fun reopenClean(ctx: Context): Boolean {
    if (handle != 0L) VvttsCore.shutdown(handle)
    handle = 0L
    native16k = false
    rateProbed = false
    return try {
      val info = ctx.applicationInfo
      val libDir = File(info.nativeLibraryDir)
      val cfgDir = File(ctx.filesDir, "eloquence")
      val h = VvttsCore.openEngine(cfgDir.absolutePath, libDir.absolutePath, language)
      if (h == 0L) return false
      handle = h
      VvttsCore.setParam(h, Eci.PARAM_INPUT_TYPE, 1)
      true
    } catch (t: Throwable) {
      Log.e(TAG, "reopen failed", t)
      false
    }
  }

  /**
   * Tests whether eciNewAudioFormat2 really switches this session to native
   * 16000 Hz render. Warmup synth (discarded), baseline synth, request the
   * 16 kHz format, synth again: trusts only a tight ~1.45x length ratio on
   * long-enough audio PLUS real energy above 5.5 kHz in the candidate.
   * Anything else — refusal, short/empty output, exception — answers false
   * and the caller reopens clean, so this can never break 11 kHz.
   */
  private fun probe16k(ctx: Context): Boolean {
    val h = handle
    if (h == 0L) return false
    return try {
      val charsetId = if (language == 0x60000) VvttsCore.CHARSET_GBK else VvttsCore.CHARSET_1252
      val out = File(ctx.cacheDir, "core_probe_out").absolutePath
      VvttsCore.synth(h, language, CAL_TEXT, charsetId, out) // warmup, discarded
      val base = VvttsCore.synth(h, language, CAL_TEXT, charsetId, out)
      if (base == null || base.size < 12000) return false
      val ret = nativeNewAudioFormat(h, OUTPUT_16K)
      if (ret < 0) {
        Log.i(TAG, "16k format refused for 0x%08x (ret=%d)".format(language, ret))
        return false
      }
      val hi = VvttsCore.synth(h, language, CAL_TEXT, charsetId, out)
      if (hi == null || hi.size < 15000) {
        Log.i(TAG, "16k probe 0x%08x: short output (%d)".format(language, hi?.size ?: -1))
        return false
      }
      val ratio = hi.size.toDouble() / base.size.toDouble()
      val hf = hfFraction(hi, OUTPUT_16K)
      val ok = ratio in 1.38..1.53 && hf > 0.01
      Log.i(TAG, "16k probe 0x%08x: %s (11k=%d 16k=%d ratio=%.3f hf=%.4f)".format(
        language, if (ok) "NATIVE" else "unproven", base.size, hi.size, ratio, hf))
      ok
    } catch (t: Throwable) {
      Log.w(TAG, "16k probe failed", t)
      false
    }
  }

  /**
   * Tests whether this session really renders 22050 Hz (ECI rate index 2).
   * Same hardening as [probe16k]: warmup, long calibration, minimums,
   * tight ~2x window, high-band witness. Restores index 1 on anything
   * but proof.
   */
  private fun probe22k(cacheDir: File): Boolean {
    val h = handle
    if (h == 0L) return false
    return try {
      val charsetId = if (language == 0x60000) VvttsCore.CHARSET_GBK else VvttsCore.CHARSET_1252
      val out = File(cacheDir, "core_probe_out").absolutePath
      VvttsCore.setParam(h, Eci.PARAM_SAMPLE_RATE, 1)
      VvttsCore.synth(h, language, CAL_TEXT, charsetId, out) // warmup, discarded
      val pcm11 = VvttsCore.synth(h, language, CAL_TEXT, charsetId, out)
      if (pcm11 == null || pcm11.size < 12000) return false
      VvttsCore.setParam(h, Eci.PARAM_SAMPLE_RATE, 2)
      val pcm22 = VvttsCore.synth(h, language, CAL_TEXT, charsetId, out)
      if (pcm22 == null || pcm22.size < 20000) {
        VvttsCore.setParam(h, Eci.PARAM_SAMPLE_RATE, 1)
        return false
      }
      val ratio = pcm22.size.toDouble() / pcm11.size.toDouble()
      val hf = hfFraction(pcm22, NATIVE_22K)
      val ok = ratio in 1.9..2.1 && hf > 0.01
      if (!ok) VvttsCore.setParam(h, Eci.PARAM_SAMPLE_RATE, 1)
      Log.i(TAG, "22k probe 0x%08x: %s (11k=%d 22k=%d ratio=%.3f hf=%.4f)".format(
        language, if (ok) "NATIVE" else "unavailable", pcm11.size, pcm22.size, ratio, hf))
      ok
    } catch (t: Throwable) {
      runCatching { VvttsCore.setParam(handle, Eci.PARAM_SAMPLE_RATE, 1) }
      Log.w(TAG, "22k probe failed, staying at 11025", t)
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
    sampleRateHz = when (hz) {
      OUTPUT_8K -> OUTPUT_8K
      OUTPUT_16K -> OUTPUT_16K
      else -> NATIVE_RATE
    }
  }

  fun setAnnotations(@Suppress("UNUSED_PARAMETER") on: Boolean) {
    // Annotations stay off on this path; speak() strips openevv backticks.
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

  fun lookUpWord(@Suppress("UNUSED_PARAMETER") volume: Int, key: String): String? = synchronized(guard) {
    substitutions[key.lowercase()]
  }

  fun forgetDictionaries() = synchronized(guard) {
    substitutions = emptyMap()
  }

  fun speak(text: String): Boolean = synchronized(guard) {
    if (closed || handle == 0L) return false
    aborted = false
    readPos = 0
    pcmBytes = ByteArray(0)
    // Volume dictionaries have no eci loader here, so their words are
    // rewritten inline (respelling or `[...] phonemes pass straight to an
    // annotations-on engine). Whole words only, case-insensitive.
    val subs = substitutions
    val rewritten = if (subs.isEmpty()) text else WORD.replace(text) { m ->
      subs[m.value.lowercase()] ?: m.value
    }
    // Strip only the incompatible service annotations (`pp phrase switch,
    // `vb carrying openevv pitch). `vs speed, `p pauses and user backtick
    // content stay for the engine to interpret.
    VvttsCore.setParam(handle, Eci.PARAM_INPUT_TYPE, 1)
    val clean = ANNOTATION.replace(rewritten, "")
    if (clean.isBlank()) return false
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
        .encode(java.nio.CharBuffer.wrap(clean))
      val b = ByteArray(bb.remaining())
      bb.get(b)
      b
    } catch (e: Exception) {
      Log.e(TAG, "encode failed", e)
      return false
    }
    val charsetId = if (language == 0x60000) VvttsCore.CHARSET_GBK else VvttsCore.CHARSET_1252
    // 16 kHz output is native-proven render or honest 11 kHz — never an
    // upsample. A stale 16 kHz format session serving other rates would
    // play slow ("lumulubog"), so leaving 16 kHz reopens fresh. Discovery
    // runs once per session, on the first 16 kHz utterance only.
    val ctx0 = appContext ?: return false
    val want16 = sampleRateHz == OUTPUT_16K
    if (!want16 && native16k) {
      if (!reopenClean(ctx0)) return false
    }
    if (want16 && !native16k && !rateProbed) {
      rateProbed = true
      if (rateBridgeLoaded && probe16k(ctx0)) {
        probed16k = true
        native16k = true
      } else {
        // The format attempt may have left state behind: reopen clean
        // before the 22k probe and normal output.
        if (!reopenClean(ctx0)) return false
        native22k = probe22k(ctx0.cacheDir)
      }
    }
    if (want16 && !native16k && !native22k) {
      // No proof: report and render honest 11 kHz for this utterance.
      sampleRateHz = NATIVE_RATE
      lastRender = "native 11 kHz (16k unproven)"
    }
    // Native render rate: a proven 16 kHz format session renders 16 kHz
    // untouched; else 22050 when the probe proved it (16 kHz output only),
    // native 8000 for 8 kHz output, else 11025.
    val renderIdx = when {
      native16k -> -1
      sampleRateHz == OUTPUT_16K && native22k -> 2
      sampleRateHz == OUTPUT_8K -> 0
      else -> 1
    }
    if (renderIdx >= 0) VvttsCore.setParam(handle, Eci.PARAM_SAMPLE_RATE, renderIdx)
    val ctx = appContext ?: return false
    val outFile = File(ctx.cacheDir, "core_pcm_out")
    var pcm = VvttsCore.synth(handle, language, encoded, charsetId, outFile.absolutePath)
    var renderedRate = when (renderIdx) {
      -1 -> OUTPUT_16K
      2 -> NATIVE_22K
      0 -> OUTPUT_8K
      else -> NATIVE_RATE
    }
    if (pcm == null && renderIdx == 0) {
      Log.w(TAG, "native 8k refused, falling back to 11025 render")
      VvttsCore.setParam(handle, Eci.PARAM_SAMPLE_RATE, 1)
      pcm = VvttsCore.synth(handle, language, encoded, charsetId, outFile.absolutePath)
      renderedRate = NATIVE_RATE
    }
    val native = pcm ?: return false
    if (aborted) return false
    // Output stage, pure native: proven 16 kHz renders pass through
    // untouched; 22k renders downsample (real content); 8k native passes
    // through; everything else is honest native render at the reported
    // rate. No EQ, no upsampling — bit-transparent by policy.
    val outPcm: ShortArray = when {
      native16k && sampleRateHz == OUTPUT_16K -> native
        .also { lastRender = "native 16 kHz" }
      native16k -> resampleLinear(native, OUTPUT_16K, sampleRateHz)
        .also { lastRender = "native 16 kHz downsampled" }
      renderedRate == NATIVE_22K -> resampleLinear(native, NATIVE_22K, OUTPUT_16K)
        .also { lastRender = "native 22 kHz downsampled" }
      renderedRate == OUTPUT_8K -> native
        .also { lastRender = "native 8 kHz" }
      sampleRateHz == OUTPUT_8K -> resampleCubic(native, NATIVE_RATE, OUTPUT_8K)
        .also { lastRender = "native 11 kHz downsampled" }
      else -> native
        .also { if (!lastRender.startsWith("native 11")) lastRender = "native 11 kHz" }
    }
    val bytes = ByteArray(outPcm.size * 2)
    for (i in outPcm.indices) {
      val v = outPcm[i].toInt()
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
