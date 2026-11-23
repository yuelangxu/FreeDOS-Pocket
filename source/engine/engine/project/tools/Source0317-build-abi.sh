#!/usr/bin/env bash
# Uses a dedicated per-ABI build directory, one compiler worker, no device access.
set -euo pipefail
engine="$(cd "$(dirname "$0")/.." && pwd)"
cd "$engine"
abi="${1:?Pass an Android ABI}"
case "$abi" in arm64-v8a|armeabi-v7a|x86|x86_64) ;; *) echo "Unsupported ABI: $abi" >&2; exit 2;; esac
ndk="$(cygpath -au "${2:?Pass the Windows Android NDK r28 directory}")"
work="$engine/build/compat-$abi"
stage="$engine/project/android-app/build/universal-native/$abi"
test ! -e "$work" && test ! -e "$stage" || { echo "Use a fresh build directory for $abi" >&2; exit 2; }
export MAKEFLAGS=-j1 GNUMAKEFLAGS=-j1 FDP_TARGET_ABI="$abi" FDP_EXPERIMENTAL_ARM_ASM=0
export PATH="$ndk:$ndk/toolchains/llvm/prebuilt/windows-x86_64/bin:/usr/bin:$PATH"
python3 "$engine/rebuild/prepare-native-tree.py" --companion-root "$(cygpath -am "$engine")" --output "$(cygpath -am "$work/jni")"
( cd "$work"; "$ndk/ndk-build.cmd" -j1 "NDK_PROJECT_PATH=$(cygpath -am "$work")" "BUILD_HOST=$abi" NDK_ENV=windows-x86_64 "NDK_ROOT=$(cygpath -am "$ndk")" "APP_ABI=$abi" APP_PLATFORM=android-21 NDK_TOOLCHAIN_VERSION=clang )
for dep in ffi pixman glib; do bash "$engine/rebuild/deps.sh" "$dep" "$(cygpath -am "$ndk")" 1; done
bash "$engine/rebuild/core.sh" "$abi" "$(cygpath -am "$ndk")" 1
mkdir -p "$stage"
for n in SDL2 compat-SDL2-addons compat-SDL2-ext compat-limbo compat-musl glib-2.0 limbo pixman-1 qemu-system-x86_64; do
  lib="lib$n.so"
  if [[ $abi == arm64-v8a ]]; then cp "$work/obj/local/$abi/$lib" "$stage/$lib"; else llvm-strip --strip-unneeded -o "$stage/$lib" "$work/obj/local/$abi/$lib"; fi
done
