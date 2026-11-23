# FreeDOS Pocket 0.3.20 engine source

The application package is `org.freedos.pocket.community` (version code 24,
minimum Android API 21, target API 35).

Verify this companion against the signed APK with Python 3.11+:

    python verify-archive.py ../FreeDOS-Pocket-0.3.20-engine-source.zip --apk ../FreeDOS-Pocket-0.3.20.apk

The archive includes the upstream sources, local native patches and Android
source. FreeDOS guest sources remain in the separate guest-source archive.

Build the native libraries with Windows Android NDK r28 and MSYS2 Bash,
Make, Autotools, Flex, Bison, Git and Python. From this engine directory:

    for abi in arm64-v8a armeabi-v7a x86_64 x86; do bash rebuild/build-abi.sh "$abi" /path/to/ndk/28.0.13004108 || break; done

The APK's ARM64 build replaces the QEMU coroutine object and relinks
QEMU from retained objects. The updated QEMU patch is also applied
by the clean native recipe. The source fingerprint check does not assert
that independent toolchains will generate bit-identical native binaries.

To rebuild the Android wrapper, use JDK 17+, Android SDK 35 and Gradle
8.14. The binary guest seed disks are not checked into Git. For an
equivalent preinstalled APK, place the 0.3.20 APK's `assets/seed/` files
under a staging directory's `seed/`, and its `assets/bios/`,
`assets/guest/` and `assets/licenses/` common files under the app's
`assets/` directory. The Gradle validation task also needs the baseline
public seed in `app/src/publicRelease/assets/seed/` (from 0.3.17).
Use absolute staging and native paths with:

    gradle -p project/android-app :app:assemblePersonalCommunityUniversalRelease "-PfdpUniversalNative=<native-root>" "-PfdpPreinstalledAssets=<staging-directory>"

For distribution, align the unsigned APK with `zipalign -P 16 4` and sign
with your own release key. Another signing key cannot update an official
installation in place.

The path repair in `project/tools/Fix-DosDevelPaths.py` works on a **staged
RAW disk copy**, not a live Android disk. Existing installations retain
their disks when updating; do not clear app storage to get new launchers.
