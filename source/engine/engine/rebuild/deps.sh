#!/usr/bin/env bash
# Run with the project MSYS2 bash, after Build-CompatX64Native.ps1.
# Usage: bash tools/Build-CompatX64Deps.sh <pixman|ffi|glib> <NDK-Windows-path> [jobs]
set -euo pipefail
export PATH="/usr/bin:$PATH"
project="$(cd "$(dirname "$0")/.." && pwd)"
abi="${FDP_TARGET_ABI:-x86_64}"
work="$project/build/compat-$abi"
jni="${FDP_PREPARED_JNI:-$work/jni}"
case "$abi" in
  arm64-v8a) triple=aarch64-linux-android; host=$triple ;;
  x86_64) triple=x86_64-linux-android; host=$triple ;;
  x86) triple=i686-linux-android; host=$triple ;;
  armeabi-v7a) triple=armv7a-linux-androideabi; host=arm-linux-androideabi ;;
  *) echo "Unsupported experimental build ABI: $abi" >&2; exit 2 ;;
esac
ndk="$(cygpath -au "$2")"
toolchain="$ndk/toolchains/llvm/prebuilt/windows-x86_64"
export PATH="$toolchain/bin:$PATH"
export CC="clang --target=${triple}21" CXX="clang++ --target=${triple}21" AR=llvm-ar RANLIB=llvm-ranlib NM=llvm-nm STRIP=llvm-strip
export CFLAGS="-O2 -fPIC -Wno-error=implicit-function-declaration -Wno-error=incompatible-pointer-types"
export LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
jobs="${3:-8}"
test -d "$jni/pixman" && test -f "$toolchain/bin/clang.exe"
component_dir="$work/autotools/$1"
pix_flags=()
if [[ $abi == armeabi-v7a && $1 == pixman && ${FDP_EXPERIMENTAL_ARM_ASM:-0} != 1 ]]; then
    # The vintage GNU-as .func/.elseif syntax is not accepted by NDK r28's
    # integrated assembler. Build the optimized C implementation for the probe.
    component_dir="$work/autotools/pixman-portable"
    pix_flags=(--disable-arm-simd --disable-arm-neon --disable-arm-iwmmxt)
elif [[ $abi == armeabi-v7a && $1 == pixman ]]; then
    # Pixman 0.40 probes optional ARM SIMD/NEON using the NDK cpufeatures API.
    # Statically include that tiny helper, retaining the optimized rendering paths.
    component_dir="$work/autotools/pixman-armfeatures"
    mkdir -p "$component_dir/cpufeatures"
    cpu_src="$ndk/sources/android/cpufeatures"
    $CC -O2 -fPIC -c "$(cygpath -am "$cpu_src/cpu-features.c")" -o "$(cygpath -am "$component_dir/cpufeatures/cpufeatures.o")"
    llvm-ar rcs "$(cygpath -am "$component_dir/cpufeatures/libndkcpu.a")" "$(cygpath -am "$component_dir/cpufeatures/cpufeatures.o")"
    export CPPFLAGS="-I$(cygpath -am "$cpu_src")"
    export LIBS="-L$(cygpath -am "$component_dir/cpufeatures") -lndkcpu -ldl"
fi
mkdir -p "$component_dir"
cd "$component_dir"
case "$1" in
  pixman)
    test -f config.status || bash "$jni/pixman/configure" --host="$host" --build=x86_64-pc-msys --enable-shared --disable-static --disable-openmp --disable-mmx --disable-sse2 --disable-ssse3 --disable-libpng --disable-gtk "${pix_flags[@]}"
    make -C pixman -j"$jobs" libpixman-1.la
    ;;
  ffi)
    test -f config.status || bash "$jni/libffi/configure" --host="$host" --build=x86_64-pc-msys --enable-static --disable-shared --disable-multi-os-directory
    make -j"$jobs" libffi.la
    ;;
  glib)
    test -f "$work/autotools/ffi/.libs/libffi.a"
    test -f "$work/obj/local/$abi/libcompat-musl.so"
    export CFLAGS="$CFLAGS -D__LIMBO__ -D__ANDROID_HAS_SIGNAL__ -I$(cygpath -am "$jni/compat") -I$(cygpath -am "$jni/compat/musl") -I$(cygpath -am "$jni/compat/musl/include")"
    export LDFLAGS="$LDFLAGS -L$(cygpath -am "$work/obj/local/$abi") -lcompat-musl -lcompat-limbo -llog"
    export LIBFFI_CFLAGS="-I$(cygpath -am "$work/autotools/ffi/include")"
    export LIBFFI_LIBS="$(cygpath -am "$work/autotools/ffi/.libs/libffi.a")"
    test -f android.cache || cp "$jni/patches/android.cache" android.cache
    test -f config.status || bash "$jni/glib/configure" --host="$host" --build=x86_64-pc-msys --cache-file=android.cache --disable-libmount --disable-dtrace --disable-systemtap --with-pcre=internal --with-libiconv=no --disable-debug --enable-shared --disable-static
    # Source companion includes its generated autotools files. git-apply can
    # reorder their mtimes: do not implicitly regenerate them with a different
    # aclocal/autoconf while compiling, or build Android test executables here.
    flags=(-j"$jobs" -o Makefile -o ../config.status -o ../../config.status
      -o ../config.h -o ../../config.h -o "$jni/glib/configure" -o "$jni/glib/aclocal.m4"
      "CFLAGS=$CFLAGS -include $(cygpath -am "$jni/compat/musl/compat_iconv.h")")
    make -C glib/libcharset "${flags[@]}" libcharset.la
    make -C glib/gnulib "${flags[@]}" libgnulib.la
    make -C glib/pcre "${flags[@]}" libpcre.la
    make -C glib "${flags[@]}" libglib-2.0.la
    ;;
  *) echo 'Choose ffi, pixman or glib.' >&2; exit 2 ;;
esac
