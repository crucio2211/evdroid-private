# evvdroid

Eloquence as a text-to-speech engine for Android and Wear OS.

The speech comes from [openevv](https://github.com/Mudb0y/openevv), a C rebuild
of IBM's Embedded ViaVoice. This repo cross-compiles that engine for Android,
wraps it in JNI, and puts a `TextToSpeechService` around it. The engine is
vendored under `native/openevv` and is not edited in place.

## Unofficial

A hobby project. Nobody's product.

Not from IBM, Nuance, Cerence, Google, or the openevv project. None of them
have endorsed or reviewed it.

It is not a supported screen reader engine. If you set it as your TalkBack
voice and it stops talking, you have to undo that yourself. Read "Turning it
off" before you turn it on.

No warranty. See LICENSE, and read the part about language data before
redistributing anything.

## What works

* Twelve languages: US and British English, Castilian and Latin American
  Spanish, French and Canadian French, German, Italian, Polish, Japanese,
  Finnish and Brazilian Portuguese. "Force language" in the settings speaks
  every utterance in one of them no matter what the caller asked for.
  Japanese travels to the engine Shift-JIS encoded; Polish keeps its own
  letters; everything else travels in the Western set.
* Eight preset voices: Reed, Shelley, Bobby, Rocko, Glen, Sandy, Grandma,
  Grandpa. Offered through the Android voice API, so they appear separately.
* All seven voice parameters: speed, pitch, inflection, head size, roughness,
  breathiness and volume.
* Speech rate and pitch from the system settings.
* Pronunciation dictionaries.
* Android 6 and later. 64-bit arm64-v8a and x86_64 only; no 32-bit ABI is shipped.

`app/src/androidTest` contains the native dictionary and pause-mode regression checks; run them on a device or emulator.

## Turning it on

TalkBack settings, Text-to-speech settings, Preferred engine, Eloquence
(openevv).

The gear next to it opens the voice settings.

## Turning it off

TalkBack settings, Text-to-speech settings, Preferred engine, Google.

## Building

Needs the Android SDK, NDK r23 or later, JDK 17, Python 3, and bash. No Android
Studio. No Gradle install, the wrapper fetches its own.

```
export ANDROID_NDK_HOME=/path/to/ndk
./gradlew installDebug -Pevvdroid.abis=arm64-v8a
./gradlew connectedDebugAndroidTest -Pevvdroid.abis=arm64-v8a
```

First build is about four minutes per ABI. Most of that is the engine: every
build writes the language rules out of text with Python, then decompiles them
into roughly 13 MB of C. That work is per language, not per ABI, so only the
first ABI pays for it.

| property | default | effect |
| --- | --- | --- |
| `evvdroid.abis` | `arm64-v8a,x86_64` | which 64-bit ABIs to build |
| `evvdroid.rules` | `c` | `bytecode` builds in 30s and runs at under half the speed |
| `evvdroid.langs` | `lang/enus` | language modules to link in |
| `evvdroid.abiSplits` | `false` | one APK per ABI |
| `evvdroid.minify` | `false` | R8 + resource shrinking on debug builds |
| `evvdroid.legacyPackaging` | `false` | compressed native libraries (smaller APK, install unpacks) |
| `evvdroid.versionCode` | `1` | CI passes its run number so updates install |

`-Pevvdroid.abis=arm64-v8a -Pevvdroid.rules=bytecode` is the fast loop for
working on the Kotlin.

Compiled rules are the default because interrupting and restarting costs about
a third of what it costs interpreted, and a screen reader interrupts
constantly.

## Settings

Launcher icon, or the gear beside the engine in the text-to-speech settings.

Dialogs for the seven voice parameters, plus voice, force language,
force speech rate, force pitch, an output volume with a peak limiter,
sample rate (8, 11, 16, 22, 32 and 44.1 kHz, all synthesized natively),
pauses, phrase prediction, abbreviations, backquote voice tags, emoji,
numbers, camel case, punctuation, volume dictionaries and a regex dictionary.
"Speak a sample" sits under the voice rows, speaking through the same rewrites
TalkBack hears except user dictionaries, so what it says is what the settings
do. Nothing speaks on its own. A setting takes effect when you set it, and the
button is how you hear it. Force speech rate and force pitch sit at the top:
when on, TalkBack's rate and pitch are ignored and the voice's own Speed and
Pitch rows are what is heard, so there are no separate rows for them; when
off, those two rows dim because TalkBack is driving.

Six deliberate choices:

Each row is one control. A label, a bar and a number would be three stops for a
screen reader, so each row merges into a single node with
`clearAndSetSemantics`. It reads as "Speed, 20 percent" and opens a dialog
listing every value instead of a slider, opening at the one in force.

Values are percentages. The engine uses two scales (speed
runs to 250, the rest to 100), so a raw number means nothing without knowing
which. Everything is hundredths of its own range.

Speech rate from the system is a multiplier, so it is treated as one. The
engine's own speed scale is far from linear: speed 100 speaks 2.7 times the
default rate and speed 200 speaks 16.6 times. Multiplying the speed number by
the percentage runs away at the top, so a measured curve converts between the
two instead. 200 percent is about twice as fast, and very high rates saturate
at the engine's own ceiling rather than becoming unusable.

Voice settings belong to the voice. Set Reed's pitch to 55, go to Glen and come
back, and Reed is still at 55. Only "Reset voice to defaults" forgets it, and
only for the voice you are on.

Speed is the listener's, not the voice's. Six presets ship at engine speed 50
and Glen and Sandy ship at 70, half again as fast. That is IBM's data, but it
made changing voice change the pace, so a preset brings its other six
settings and leaves the speed alone.

Dialogs start at the voice's own values. Each preset carries its own head size,
inflection and volume; Reed is inflection 30 and volume 92, Bobby is 35 and 90.
Picking a voice loads that voice's numbers. A parameter you have not set is
never sent to the engine, so a fresh install sounds like Eloquence always has.
Getting this wrong is audible. An early version applied its own defaults over
every preset and made all eight voices wrong the same way.

## Pauses

The three choices go directly to the engine's native ECI pause-mode parameter:
Never (0), At end of text only (1), and Always (2). The default is At end of
text only. The engine itself applies the selected mode; Android does not
rewrite punctuation or inject pause annotations.


## Voice, phrase prediction, abbreviations

The voice is whichever the settings screen says. A caller that names a voice
does not override it, because a client holds on to the Voice object it was
given: a screen reader that connected while Reed was selected goes on asking
for Reed however many times the setting is changed underneath it. The name is
still what decides the language.

Phrase prediction is the engine guessing where the phrase boundaries in a
sentence are and shaping the intonation to match. That reads well in prose and
gets in the way in a screen reader, where a line is usually a fragment and the
guess is wrong, so it is off. It is an annotation rather than a parameter, so
it goes out with every utterance.

Expand abbreviations is `eciDictionary`, which is inverted in the engine: zero
turns it on. Off by default. It is re-sent with every utterance, since it is
the one setting with nothing to say whether something else has moved it.

## Numbers and punctuation
Both follow the project's SAPI5 driver, whose tested behavior is the
specification here rather than this text.

Thousands commas go first and always, even with everything off: 1,000
reads one thousand instead of one comma hundred, and decimals pass
through. A lone ñ is the letter's name, said as enye; inside a word it
goes in as ny, which reads as the palatal sound. Then digit runs regroup -- apart, remainder-first pairs and
triplets (12345 in pairs is 1 23 45), quadruplets whole up to four and
apart past that -- wherever they stand.

Punctuation speaks from a 288-entry symbol table of names, levels and
preserve flags. A reached level speaks the table's own name; marks flagged
to stay keep the synthesiser company as a brief pause instead of as
themselves, because this engine spells an isolated mark aloud and every
kept one came out twice. Below its level a mark stays for the engine's
default, sentence-ending .!? stays prosody below all, and dots and commas
between digits pass untouched.

`@` never reaches any of that: it is always the word `at` first, so an
address reads as words. Everything else passes to the engine untouched,
punctuation on or off: with it off the engine's own defaults speak dots
and pluses, which is what that setting means.

Big text is split before any of it, NVDA-style: each piece is rewritten
and spoken on its own, so the first audio lands after the first piece's
frontend rather than the whole block's.

## Emoji

"Speak emoji" turns every emoji into its plain-English name before anything
else sees the text: grinning face, red heart, flag Philippines. A run of
one name compresses from a chosen two to five repeats: three smiling faces
come out as 3 smiling face. The table holds Unicode 16.0 plus the Emoji
17.0 and 18.0 additions -- 5235 fully-qualified, minimally-qualified and
unqualified sequences
except single ASCII characters, which are ordinary text. Matching is
longest-first, so a family wins over its first person and a keycap over its
digit; modifiers standing alone are dropped.

## Dictionaries

Three rows pick a file each for the Main, Root and Abbreviations volumes, each
with a switch for whether it is used. A shared filename identifies its volume
and language, such as `enumain.dic`, `enuroot.dic` or `enuabbr.dic`. A fourth
row opens the regex dictionary, with its own enable switch. "Remove
dictionaries" clears the selected files and the regex dictionary.

The speech service builds one immutable snapshot for the active language. It
normalizes text files into the engine's byte encoding and preserves saved
binary dictionaries unchanged. The snapshot contains all selected volumes
together and is identified by SHA-256 content hashes, not file size or
timestamps; replacing a file with different bytes is detected even when its
size and modification time stay the same.

The native bridge loads each complete volume through OpenEVV's own
`eciLoadDict` bulk-volume API into a detached ECI dictionary handle. It does
not parse Root entries in JNI or call `eciUpdateDict` once per word. Text
volumes are normalized with the engine's required blank-line terminator,
which prevents the native reader from dropping the final entry at EOF.
Only after every selected volume succeeds does the bridge activate the new
handle and retire the previous one. A failed volume leaves the previous
complete set active. The bridge logs native load time separately for Main,
Root and Abbreviations, making a slow volume identifiable.


The service prewarms the system-locale engine as soon as Android creates the
TTS service, so dictionary loading can begin before the screen reader sends
its first utterance. A warm snapshot is checked against the source content
hashes and its manifest/file sizes; the app does not reread and hash every
generated Root volume before the native loader reads it. If the native loader
rejects a cached volume, the snapshot is rebuilt from the original source
files and retried once. Startup and per-volume native load times are logged.

Each utterance holds a native dictionary lease and a snapshot-file lease.
Replacement waits for outstanding utterances to finish, and retired snapshot
files are deleted only after their last lease closes. No per-word Kotlin
dictionary map is built, cached, or scanned during speech.

A picked file is copied into app storage. A content URI belongs to whichever
app supplied it, may not be readable later, and the speech service reads the
private copy. Text files with no usable entries are refused at import; invalid
lines are skipped while building the snapshot.

A regex dictionary is a separate text file: a pattern, a tab, and what to put
in its place, so `NVDA` with a tab and `en vee dee ay` says the letters.
Lines starting with `#` are comments, blank lines are skipped, and a pattern
that does not compile is skipped and logged rather than sinking the file.
Replacements use `$1` group references. It applies after the readability
fixes, closest to the engine, so what is written there is what gets said.
Picking a file turns it on.

The regex row opens the entries themselves: every pattern in file order, one
stop each, where pressing one offers editing it in place or deleting it after
asking. Adding offers a Match case switch, off by default so chatgpt fixes
ChatGpt too; importing a file to replace it, and exporting a copy elsewhere.
Remove all entries sits at the bottom, behind its own asking.

No dictionary is shipped here. See LICENSE.

The volume dictionaries use the active language's native ECI encoding. The
regex dictionary rewrites text and works in every language.


## Heteronyms

Off, deliberately. The engine's heteronym filter writes `[phone]
pronunciation annotations, which this engine spells aloud instead of
honouring -- measured on both sides, not assumed -- so installing it
would read every covered word as garbage. Until the bracket
pronunciation path exists, words keep their dictionary and rule
readings. A lone ñ is the letter's name instead and is rewritten to
"enye" before the engine sees it; inside words the engine's own
preprocessor reads the letter.

## Interrupting

A screen reader wants each string to cut off the one before it. Two things make
that work.

The engine cannot abandon an utterance, so long text is handed over in
sentence-sized pieces. Asking for silence then waits out a piece rather than a
whole paragraph. The rule for where a piece may end is openevv's, ported from
its NVDA driver: a sentence end is free because the engine already pauses
there, anywhere else costs about 0.4s, so a full stop has to prove itself. A
word with a dot inside it is an abbreviation, and a short capitalised word is
an initial.

Synthesis is paced to real time. The engine produces 1.3s of audio in 12 ms and
Android queues every byte of it. Swiping quickly used to leave five or six
items synthesised and queued while the first was still playing, and asking for
silence threw that queue away, so those items were never heard. Output now runs
no more than 300 ms ahead. Measured: 2229 ms of audio handed over in 700 ms of
speaking before, about 1000 ms after.

## Wear OS

Runs on a watch. The published APK is 64-bit only and does not target 32-bit watches.

A Pixel Watch has no USB, so adb is wireless. On the watch: Settings, System,
About, Versions, tap Build number seven times. Then Developer options, ADB
debugging on, Wireless debugging on, Pair new device. Watch and computer on the
same Wi-Fi, with the watch's Wi-Fi actually on rather than dropped for
Bluetooth.

```
adb mdns services                 # the watch announces both ports
adb pair 192.168.x.x:PAIRPORT     # then the six digits on screen
adb connect 192.168.x.x:PORT
adb -s SERIAL shell getprop ro.product.cpu.abilist
./gradlew installDebug -Pevvdroid.abis=arm64-v8a
```

## Layout

```
native/openevv          the engine, vendored in this repo, untouched
native/android.mk       two make targets openevv has no reason to carry
native/build-native.sh  cross-compiles the engine, links libevvjni.so
native/tools            drives openevv's own test gate over adb
app/src/main/cpp        JNI bridge
app/src/main/kotlin     the service, engine wrapper, settings
```

The engine is built by its own Makefile rather than CMake, because that
Makefile generates the language rules with Python first. Gradle runs the script
and picks the result up as prebuilt `jniLibs`.

Everything links into one shared object, `libevvjni.so`. Android only packages
files named `lib*.so`, and openevv's own library records a soname of
`libeci.so.1`, which nothing would then find.

## Engine patches

A fix to openevv that upstream has not taken yet goes in `native/patches` and is applied at build time. The vendored
engine is left as imported; patches apply on top of it. The frame-alignment fix went that way with the rest when both
landed upstream, after the adjacent-stores one before it.

`native/tools/probe-on-device.sh` makes the two device rows possible. It stands
in for openevv's probe so the gate drives a build over adb unchanged.

## Licence

MIT for the Kotlin, the C bridge, the build, the tests and the patches. See
LICENSE.

The engine sources under `native/openevv` are openevv's, MIT, under its own
copyright. They are copied into this repo so a build does not need another checkout.

Not the language data openevv carries. That is transcribed from IBM's Embedded
ViaVoice objects and is IBM's. `native/openevv/NOTICE` says what it is and
whose it is.

No pronunciation dictionary is included. Those belong to whoever wrote them,
and the best known collection carries no licence at all, so it is linked rather
than copied. The test fixture here is written for the purpose.
