#!/usr/bin/env bash
# Downloads prebuilt Apple Eloquence libs for Apple-engine mode.
# Personal builds only — files are NOT committed. See APPLE_ENGINE_NOTES.md.
#
# Source: https://github.com/GGbond2007/eloquence-tts-android
# Pinned to the commit this swap was mapped against. Bump APPLE_SHA
# deliberately after re-checking JNI compatibility.
set -euo pipefail

APPLE_REPO="${APPLE_REPO:-GGbond2007/eloquence-tts-android}"
APPLE_SHA="${APPLE_SHA:-1d14cd6a6064085ac708ba80df211f1777501dd5}"
OUT="${OUT:-$(cd "$(dirname "$0")/../app/build/native/jniLibs" && pwd)}"
OUT="${OUT//\\//}"

FILES=(
  libc++_shared.so
  libchs.so libchsrom.so
  libcht.so libchtrom.so
  libdeu.so
  libeci.so
  libeloquence_jni.so
  libeng.so libenu.so
  libesm.so libesp.so
  libfin.so
  libfra.so libfrc.so
  libita.so
  libjpn.so libjpnrom.so
  libkor.so libkorrom.so
  libptb.so
  libvvtts_core.so
)

dest="$OUT/arm64-v8a"
mkdir -p "$dest"

echo "fetching ${#FILES[@]} Apple libs ($APPLE_REPO@$APPLE_SHA) -> $dest"
for f in "${FILES[@]}"; do
  url="https://raw.githubusercontent.com/$APPLE_REPO/$APPLE_SHA/native-libs/arm64-v8a/$f"
  echo "  $f"
  curl -fL --retry 3 -o "$dest/$f" "$url"
  if [ ! -s "$dest/$f" ]; then
    echo "empty download: $f" >&2
    exit 1
  fi
done

ls -l "$dest"
echo "apple libs are under $dest (arm64-v8a only)"
