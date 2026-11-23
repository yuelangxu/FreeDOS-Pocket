#!/usr/bin/env bash
# Build only inside the ABI's isolated JNI/obj tree. Never run upstream install.
# Usage: bash tools/Build-CompatCore.sh <abi> <Windows-NDK-path> [jobs]
set -euo pipefail
export PATH="/usr/bin:$PATH" PKG_CONFIG=false PYTHONWARNINGS="ignore::SyntaxWarning"
abi="$1"
case "$abi" in
  armeabi-v7a) pixman=pixman-portable ;;
  arm64-v8a|x86|x86_64) pixman=pixman ;;
  *) echo "Unsupported experimental build ABI: $abi" >&2; exit 2 ;;
esac
project="$(cd "$(dirname "$0")/.." && pwd)"
work="$project/build/compat-$abi"
jni="$work/jni"
ndk="$(cygpath -au "$2")"
jobs="${3:-8}"
llvm="$ndk/toolchains/llvm/prebuilt/windows-x86_64/bin"
export PATH="$llvm:$PATH" TMPDIR="$work/tmp"
mkdir -p "$TMPDIR"
dest="$work/obj/local/$abi"
test -f "$jni/qemu/hw/audio/sb16.c"
test -f "$dest/libSDL2.so"
test -f "$work/autotools/$pixman/pixman/.libs/libpixman-1.so"
test -f "$work/autotools/glib/glib/.libs/libglib-2.0.so"
cp -u "$work/autotools/$pixman/pixman/.libs/libpixman-1.so" "$dest/libpixman-1.so"
cp -u "$work/autotools/glib/glib/.libs/libglib-2.0.so" "$dest/libglib-2.0.so"
cd "$jni"
common=(-o ndk "LIMBO_JNI_ROOT=$jni" "NDK_ROOT=$ndk" NDK_ENV=windows-x86_64 "BUILD_HOST=$abi"
    "APP_ABI=$abi" BUILD_GUEST=x86_64-softmmu USE_KVM=false USE_AAUDIO=false
    "BUILD_THREADS=$jobs" "ARCH_LD_FLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -latomic"
    "ENV_EXTRA=-I$work/autotools/glib/glib -I$work/autotools/$pixman/pixman"
    "OBJCOPY=$llvm/llvm-objcopy" "RANLIB=$llvm/llvm-ranlib" "NM=$llvm/llvm-nm" "LD=$llvm/ld.lld")
test -f qemu/config-host.mak || make "${common[@]}" config-qemu
make "${common[@]}" _qemu
"$llvm/llvm-readelf" -hlWd "$dest/libqemu-system-x86_64.so"
