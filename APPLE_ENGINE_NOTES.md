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

## Known gaps vs openevv build

- arm64-v8a only. No armeabi-v7a (watches) or x86_64 (emulator).
- Voice names differ: evvdroid offers Reed/Shelley/Bobby/Rocko/Glen/Sandy/
  Grandma/Grandpa; Apple has Reed/Shelley/Sandy/Rocko/Flo/Grandma/Grandpa/Eddy.
  Mapping: Bobby->Eddy, Glen->Flo (*gender flips, no exact equivalent*).
- Volume dictionaries (`eciLoadDict`/`eciUpdateDict`) are no-ops on this path;
  `vvtts_core` exposes no dict APIs. Regex dictionary, emoji, numbers,
  punctuation, pauses still work (Kotlin-side).
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
