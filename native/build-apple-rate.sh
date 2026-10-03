#!/usr/bin/env bash
# Compiles the Apple-engine micro-JNI rate bridge (evv_rate.c) for arm64.
# Personal/experimental builds only. See APPLE_ENGINE_NOTES.md.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)

OUT=${OUT:-"$root/app/build/native/jniLibs"}
OUT=${OUT//\\//}
ABI=${ABI:-arm64-v8a}
API=${API:-21}

NDK=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}
if [ -z "$NDK" ]; then
	echo "set ANDROID_NDK_HOME to an NDK r23 or later" >&2
	exit 1
fi
NDK=${NDK//\\//}

case "$(uname -s)" in
	Linux*)  hosttag=linux-x86_64 ;;
	Darwin*) hosttag=darwin-x86_64 ;;
	*)       hosttag=windows-x86_64 ;;
esac
bin="$NDK/toolchains/llvm/prebuilt/$hosttag/bin"
exe=""
[ -x "$bin/clang" ] || exe=".exe"
clang="$bin/clang$exe"
strip="$bin/llvm-strip$exe"
if [ ! -x "$clang" ]; then
	echo "no clang at $clang" >&2
	exit 1
fi

mkdir -p "$OUT/$ABI"
"$clang" --target=aarch64-linux-android$API \
	-O2 -w -fPIC -fvisibility=hidden \
	-shared \
	"$root/app/src/main/cpp/evv_rate.c" \
	-llog \
	-o "$OUT/$ABI/libevvrate.so"
"$strip" "$OUT/$ABI/libevvrate.so"
ls -l "$OUT/$ABI/libevvrate.so"
echo "rate bridge is under $OUT/$ABI"
