# Apple-engine mode (personal builds only)

This branch (`apple-engine-swap`) adds an optional Apple-Eloquence backend to
evvdroid. Default builds are unchanged (openevv).

## Personal use only

The Apple backend uses prebuilt binaries from
https://github.com/GGbond2007/eloquence-tts-android
(`native-libs/arm64-v8a`: `libeci.so` + 14 language `.so` + rom `.so` +
`libvvtts_core.so` + `libeloquence_jni.so` aux).

Those voice/engine binaries are derived from Apple's tvOS runtime and are
copyright Apple Inc. The upstream repo carries a research-only disclaimer.
Do NOT redistribute the APK, upload it anywhere public, or commit the `.so`
files to git — not even to this private repo. Use GitHub Actions here only
as a personal builder (your PC cannot build), then download the artifact for
your own device and delete it afterwards if you want to be careful.

This repo never commits the `.so` files:

- `native/fetch-apple-libs.sh` downloads the 22 files at build time into
  `app/build/native/jniLibs/arm64-v8a/` (Gradle build dir, git-ignored).
- Pinned to `APPLE_SHA=1d14cd6a6064085ac708ba80df211f1777501dd5`. Bump only
  after re-checking `vvtts_core` JNI compatibility.
- CI job `build-apple` packages them into the APK artifact (`evvdroid-apple-release`).
  The artifact itself contains Apple binaries — treat it as personal, not a release.

Code vendored from upstream (MIT for code, NOT for voice data):

- `app/src/main/java/com/xw/vvtts/core/VvttsCore.java`
- `app/src/main/java/com/xw/vvtts/utils/KonaVoice.java`
- `eci.ini` layout + Klatt-hook / no-`eciDelete` notes from `jni/vvtts_core.c`

## How to build

Local (needs SDK + JDK 17, no NDK needed on this path):

```
./gradlew assembleRelease -Pevvdroid.engine=apple -Pevvdroid.abis=arm64-v8a
```

GitHub (personal):

- Push to `apple-engine-swap` or Run workflow manually.
- Download `evvdroid-apple-release` artifact.
- Install on an arm64 phone (Android 6+; `minSdk 23`).

## What the swap does

- New: `app/src/main/kotlin/org/evvdroid/AppleEngine.kt` — blocking-synth
  adapter with the same `speak()/read()/stop()` contract as `EvvEngine`.
- `EvvEngine` delegates to `AppleEngine` when `BuildConfig.APPLE_ENGINE` is
  true (`-Pevvdroid.engine=apple`). Service, preview, settings screens and
  the text pipeline (emoji, numbers, regex, pauses) are unchanged.
- `EvvEngine.init(context)` must run first (wired in `EvvTtsService.onCreate`
  and `SettingsModel` init) so Apple sessions can write `eci.ini` and find
  `nativeLibraryDir`.
- `available` becomes the fixed 14 Apple dialects:
  en-US, en-GB, es-ES, es-MX, fr-FR, fr-CA, de-DE, it-IT,
  zh-CN, zh-TW, pt-BR, ja-JP, fi-FI, ko-KR (all `Eci.localeOf`-compatible).
- Sample rate is fixed at 11025 Hz.

## Backquote annotations (fixed)

The service prepends openevv backtick annotations to every piece
(`Prosody.prefix` `pp0/`pp1, `Prosody.voice` `vsN/`vbN, `Pauses` `pN).
`AppleEngine` strips only the incompatible two — `pp` (no Apple
equivalent) and `vb` (carries openevv pitch here, breathiness on Apple;
pitch travels via `setVoiceParam` instead). `vs speed and `p pauses
match Apple syntax and pass through, as does all other user backtick
content (`` `[...] `` phonemes, Apple `vN/`vv voice tags): annotation
input (`PARAM_INPUT_TYPE=1`, which Apple defaults off) is enabled per
session and re-asserted per utterance, so the engine interprets them
instead of speaking "back quote ...".

## Dictionaries on the Apple path

`vvtts_core` exposes no `eciLoadDict`/`eciUpdateDict`, so volume
dictionaries cannot be taught to the engine. Instead the service pushes
the parsed word maps (`EvvTtsService.loadDictionaries` flattens
main/root/abbreviation, main wins) via
`EvvEngine.setAppleDictionaries` and `AppleEngine.speak()` rewrites
whole words inline before synthesis — respellings and `` `[...] ``
phoneme says alike. Regex dictionaries already rewrite text upstream
and keep working. Per-word teaching is skipped there; `lookUpWord`
answers from the pushed map (used by tests).

## 16 kHz output (ported from IBMTTS-16k-tune)

The NVDA patch has two halves: (1) binary `.syn` module patches so the
Windows engine renders 16 kHz natively, and (2) an output DSP stage
(RBJ presence + air shelves + anti-click ramp + clamp) with a 16 kHz
player. Only (2) ports: the Apple engine renders 11025 Hz fixed
(`SetParam(5,1)`; 22050 is refused), and its `.so` layout has no patch
pack, so there is nothing to binary-patch.

Ported (`AppleEngine` companion):

- Native-22k probe (`probe22k`): at session open, one ASCII word is
  synthesized at rate index 1 and 2; only a ~2x PCM length ratio trusts
  22050. Then 16 kHz output downsamples genuine content (cubic) instead
  of upsampling 11 kHz. 8 kHz renders natively with an 11 kHz fallback.
  Refusal/empty/crash restores index 1 — 11 kHz can never break.
- Native-16k format experiment (`probe16k`, `libevvrate.so`): disassembly
  of `libeci.so` shows `SynthThread::setEngineSampleRate` explicitly
  branching on 16000/11025/8000, and `SynthThread::newAudioFormat`
  reading the requested rate at `ECIaudioFormat+8` — so a 16 kHz output
  format is a first-class citizen, not a hack. `evv_rate.c` (compiled in
  CI by `native/build-apple-rate.sh`) calls the exported
  `eciNewAudioFormat2` with a zeroed format carrying only rate=16000 at
  +8; calibration trusts only a ~1.45x PCM ratio, else the session is
  reopened clean and the 22k/11k paths take over. Proven 16 kHz renders
  go straight to DSP with no resampling at all. (Also proven dangerous
  to skip: `vvtts_voice.c`'s `eci+224` is `ECIstate*`, not `SynthThread*`,
  on Apple libeci — Guangrong offsets do not transfer.)

- `resampleLinear`: 11025 -> 16000 bandlimited windowed-sinc (32 taps,
  Hann, 256 phases, cached per rate pair, unity-gain rows) — replaces
  linear/cubic, whose imaging aliases above 5.5 kHz the shelves then
  boosted as harshness. 22050 -> 16000 likewise; 8 kHz downsample stays
  cubic (aliasing matters less there). Klatt-hook native render was
  ruled out: the published ECI header carries no Klatt structs (Apple
  extension, undocumented layout) — blind structs would corrupt audio.
- `dsp16k`: presence s1 +9 dB @ 3 kHz + air +5 dB @ 7 kHz (RBJ high-shelf,
  S=1, fs=16000 — same formulae as `_ibmeci._shelf_coeffs`), 32-sample
  (2 ms) utterance-start ramp, int16 clamp. s1 because presence-contour
  defaults on in the NVDA tune; 8/11 kHz stay bit-transparent like NVDA.
- Settings offers 8000/11025/16000 on the Apple path only
  (`SettingsModel.RATES`); `Eci.coerceSampleRate` keeps 16000 stored, and
  the openevv path safely falls back to 11025 for it.
- Presence contour toggle (same as the NVDA tune's Presence16k, default
  on): s1 is the full +9 dB presence + air above, s0 the gentle +1.5 dB
  remap (`Settings.presenceContour` -> `AppleEngine.setPresenceContour`,
  preview included). If 16 kHz sounds harsh on the phone speaker, switch
  it off before judging the resampler.

Pick "16000" in voice settings for the tuned output. Default stays 11025.

Native-render note (investigated Oct 2026): `libeci.so` strings show no
16 kHz/22.05 kHz support, only `setEngineSampleRate` driven by the voice
data; the Klatt vocoder in these Mach-O->ELF blobs is fixed-rate and
`SetParam(5,2)` is refused upstream. The P16D blobs target Windows `.syn`
byte offsets and do not apply to ELF `.so` layout, and no pack exists for
it — so a native `.so` patch is not portable. Cubic upsample + DSP above
is the closest achievable output stage.

Force-patch attempt (mapped Oct 2026, NOT applied): extracted
`p16all.pth` (29 entries) from the NVDA addon and decoded ENU.p16s1
(21 runs). s1 vs s0 differ in exactly one run — the 4096-byte appended
presence tail. The shared anchor at `enu.syn:1296561` replaces
`8b 85 36 0a` (x86 `mov eax,[ebp+...]`) with `b8 06000000` (`mov eax,6`)
+ `90` nop: a Windows-x86 code patch. Apple `.so` are ARM64 ELF converted
from Apple dylibs — no corresponding offsets, opcodes, or tables exist,
so writing these bytes would corrupt the libs. Not applied; cubic + DSP
stands. If Apple-side 16 kHz voice data ever surfaces, it plugs into
`native/fetch-apple-libs.sh`.

Element-by-element map (`.syn` patch -> Apple `.so`):

| .syn element | Apple `.so` analog? | Evidence |
|---|---|---|
| Header rate field `11 2b`->`80 3e` (11025->16000 u16, e.g. `enu.syn:89792`) | None — `11 2b` occurs 5-13x per lib, all inside ELF metadata (`.dynsym`, `.gnu.hash`, `.rela.dyn`) or coincidental ARM64 bytes; no single voice-descriptor field | section-mapped byte scan of `libenu.so` |
| Anchor `8b85360a`->`b8060000`+`90` (x86 `mov eax,6` rate selector, all 13 voices) | None — ARM64 has no x86 stub; no common stub pattern across the 14 voice libs | per-lib scan, format mismatch |
| Header/metadata runs (`.xflt` ptr, sizes, checksums) | None — `.syn` container format; Apple uses Mach-O sections (`.m2e_data` etc.) | ELF section parse |
| Appended 4096/8192-byte retuned Klatt tables | None — Apple voice-data format undocumented; no transplant target | format mismatch |
| s1 presence stage (flag + 5 coeff floats) | Ported as output DSP instead (same curve family, see 16 kHz section) | blob float decode |
| Output DSP (+9/+1.5 dB @3 kHz, +5 dB @7 kHz, ramp, clamp) | Ported exactly (`AppleEngine.dsp16k`, verified vs `eloq61_sapi5.c:1094-1125`) | line-by-line match |

## Crash hardening

- `proguard-rules.pro` keeps `VvttsCore` natives (+`KonaVoice`): release
  builds minify, and R8 would otherwise rename the methods
  `libvvtts_core.so` looks up by name.
- `AppleEngine.ensureHandle` catches `Throwable` (a broken native arrives
  as `Error`, not `Exception`) and `EvvEngine.open` guards the Apple
  branch, so engine failure degrades to "no engine" instead of killing
  the settings screen or service.
- If the launcher still crashes, capture it with:
  `adb logcat -d | Select-String "evvdroid|VvttsCore|AndroidRuntime"` and
  paste the `FATAL EXCEPTION` + `signal` lines — a native `SIGSEGV`
  inside `libeci.so` needs the crashing address/symbol, Java-side needs
  the stack.
- No PC needed anymore: the settings app records Java crashes itself
  (`CrashLog`, `filesDir/crash-last.log`) and shows the report in plain
  text on the next launch instead of dying again. Read or copy it back.
  A crash with NO new report is native (handler never fires) — that
  absence is the diagnosis.

## Language names (fixed)

`Eci.displayName` no longer relies on `java.util.Locale` with ISO-3 codes,
which read raw codes ("cmn") or nothing for Chinese/Japanese in the
Force-language dropdown. `Eci.DISPLAY_NAMES` hardcodes all 21 languages
("Chinese (Simplified)", "Japanese", ...); unknown ids fall back to Locale
then hex. TTS matching (`localeOf`) is unchanged.

## Known gaps vs openevv build

## Known gaps vs openevv build

- arm64-v8a only. No armeabi-v7a (watches) or x86_64 (emulator).
- Voice names differ: evvdroid offers Reed/Shelley/Bobby/Rocko/Glen/Sandy/
  Grandma/Grandpa; Apple has Reed/Shelley/Sandy/Rocko/Flo/Grandma/Grandpa/Eddy.
  Mapping: Bobby->Eddy, Glen->Flo (*gender flips, no exact equivalent*).
- Volume dictionaries work by inline word substitution (see above), not the
  engine loader; regex dictionary, emoji, numbers, punctuation still work
  (Kotlin-side). Pauses use the engine's native `p marks; `pp phrase
  prediction is stripped (no Apple equivalent, engine default applies).
- Stop is best-effort: `nativeStop` is a no-op upstream (calling `eciStop`
  crashes); interruption lands on piece boundaries (`TextPieces.split`).
- `eciDelete` is never called (upstream crash); sessions live for the process.
- 58 device tests in `app/src/androidTest` were written for openevv
  (Polish UTF-8 path, Western bytes, interrupt timing). Expect failures on the
  Apple path until the gate is re-baselined; CI `build-apple` compiles the app
  only, no `connectedDebugAndroidTest`.

## Reverting

```
git checkout main
./gradlew assembleRelease   # openevv again
```

Delete the branch when done with personal testing to avoid accidental
redistribution.
