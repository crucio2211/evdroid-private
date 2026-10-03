#!/usr/bin/env bash
# Apple-engine micro-JNI rate bridge (evv_rate.c) for arm64.
# With an NDK (ANDROID_NDK_HOME) it compiles fresh; without one (small
# local setups) it copies the checked-in prebuilt instead, so the NDK is
# optional. Personal/experimental builds only. See APPLE_ENGINE_NOTES.md.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)

OUT=${OUT:-"$root/app/build/native/jniLibs"}
OUT=${OUT//\\//}
ABI=${ABI:-arm64-v8a}
API=${API:-21}

NDK=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}
NDK=${NDK//\\//}

case "$(uname -s)" in
	Linux*)  hosttag=linux-x86_64 ;;
	Darwin*) hosttag=darwin-x86_64 ;;
	*)       hosttag=windows-x86_64 ;;
esac
clang=""
if [ -n "$NDK" ]; then
	bin="$NDK/toolchains/llvm/prebuilt/$hosttag/bin"
	exe=""
	[ -x "$bin/clang" ] || exe=".exe"
	if [ -x "$bin/clang$exe" ]; then
		clang="$bin/clang$exe"
		strip="$bin/llvm-strip$exe"
	fi
fi

mkdir -p "$OUT/$ABI"
if [ -n "$clang" ]; then
	"$clang" --target=aarch64-linux-android$API \
		-O2 -w -fPIC -fvisibility=hidden \
		-shared \
		"$root/app/src/main/cpp/evv_rate.c" \
		-llog \
		-o "$OUT/$ABI/libevvrate.so"
	"$strip" "$OUT/$ABI/libevvrate.so"
	echo "compiled fresh with $clang"
else
	# No NDK around: our own 4 KB bridge, checked in, instead.
	cp "$here/prebuilt-rate/$ABI/libevvrate.so" "$OUT/$ABI/libevvrate.so"
	echo "no NDK; copied prebuilt libevvrate.so"
fi
ls -l "$OUT/$ABI/libevvrate.so"
echo "rate bridge is under $OUT/$ABI"
