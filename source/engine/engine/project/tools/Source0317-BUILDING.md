# FreeDOS Pocket 0.3.17 public engine source

The Android package is `org.freedos.pocket.community`, version code 21.
It targets API 35, declares API 21 as its minimum, and uses four Android
native CPU backends to run the same x86 DOS programs. The FreeDOS guest
sources are in the separate companion. This ZIP contains no private disk,
signing key, or software obtained from a user's device.

## Check the sources against the public APK

With Python 3.11+, from the extracted `engine` directory:

    python verify-archive.py ../FreeDOS-Pocket-0.3.17-engine-source.zip --apk ../FreeDOS-Pocket-0.3.17-public-release.apk

The verifier hashes every listed source and all 36 native libraries in the
APK. On a pristine extraction, run `python verify.py` to replay upstream
patch checks. The archives, patches and license notices are included.

## Build the four native backends

Use Windows Android NDK r28 (28.0.13004108) and MSYS2 Bash, Make, Autotools,
Flex, Bison, pkg-config, Git and Python 3.11+ (able to open Windows paths).
Use a short path without spaces. Run sequentially from MSYS2, preferably
without an emulator running:

    for abi in arm64-v8a armeabi-v7a x86_64 x86; do bash rebuild/build-abi.sh "$abi" /path/to/ndk/28.0.13004108 || break; done

The recipe prepares each native tree and includes the JNI/SDL modules, FFI,
Pixman, GLib and QEMU TCG backend. ARMv7 uses Pixman's portable C paths.
The Android host is selected by the ABI; the QEMU x86_64-softmmu library
name refers to the emulated PC, not the phone's CPU. Each build uses one
compiler worker. The ARM64 staged files match the retained production
objects; the other three use NDK llvm-strip. The source checks and release
fingerprints are not a claim of bit-identical independent recompilation.

## Assemble the Android application

Install JDK 17+, Android SDK/build-tools 35 and Gradle 8.14. Recover and
hash-check the offline public assets from the accompanying original APK:

    python rebuild/stage-public-assets.py ../FreeDOS-Pocket-0.3.17-public-release.apk public-assets

Copy `public-assets/bios`, `guest`, and `licenses` into
`project/android-app/app/assets/`, and `public-assets/seed` into
`project/android-app/app/src/publicRelease/assets/`. If rebuilding the
optional input floppy, see `project/guest-tools/hostime/HOSTIME.ASM` and
`project/tools/Create-HostImeFloppy.py`.

In MSYS2, from the extracted engine directory:

    gradle --no-daemon --max-workers=1 -p project/android-app :app:assemblePublicUniversalRelease "-PfdpUniversalNative=$(cygpath -am project/android-app/build/universal-native)"

This produces an unsigned APK. For your own distribution, zipalign
(`-P 16 4`) before signing, and use your own private key with APK signing
schemes v1/v2/v3 for API 21+. A different key cannot update the official
application in place. Never uninstall an existing copy to force an update:
its DOS disks are stored in the application's private data.
