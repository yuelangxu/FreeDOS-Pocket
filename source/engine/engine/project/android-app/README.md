# FreeDOS Pocket

FreeDOS Pocket is a native Android application that runs an offline x86
FreeDOS environment through an embedded Limbo/QEMU engine. It does not require
root, Termux, Electron, a browser runtime, or an internet connection to launch.

## Project location

`D:\Projects\Android-FreeDOS`

## Public release

- Android label: `FreeDOS Pocket`
- package ID: `org.freedos.pocket.community`
- version: `0.3.17-public` (code 21), minimum Android 5.0 / API 21, target API 35
- architectures in a single APK: 32-bit ARM, 64-bit ARM, 32-bit x86 and x86-64
- [Share folder](public-distribution/0.3.17/) (APK, both corresponding-source archives, installation notes and checksums)
- [APK to install](public-distribution/0.3.17/FreeDOS-Pocket-0.3.17-public-release.apk)
- [Actual Android 6 x86 test](verification/android-compat-20261122/api23-x86/public0317-smoke.json): the signed APK selected the native 32-bit engine and ran DOS `VER`. All 36 libraries passed 16 KiB packaging/alignment checks; not every device or game has been tested.
- [Android 17 in-place update test](verification/android-compat-20261122/api37-16k/public0317-upgrade.json): the same APK ran natively on x86-64 with 16 KiB pages and read the original DOS C: marker after upgrading from 0.3.16. [Lifecycle test](verification/android-compat-20261122/api37-16k/public0317-lifecycle.log): background pause, foreground resume, two orientations and orderly shutdown after removal from Recents passed.
- [Redmi Pad SE update](verification/android-compat-20261122/redmi-0317-inplace.json): installed in place from 0.3.15 on Android 14 / ARM64; verified the installed bytes, unchanged app ID and first-install timestamp. [Live boot](verification/android-compat-20261122/redmi-0317-live.json) confirmed the public engine was running with its two existing disk paths and a visible `C:\>` prompt, without sending any input. No uninstall, root or clearing storage.
- [Private game-fixture tests](verification/game-compatibility/public0317-visual-review-20261123.json) and [DOOM pistol sound measurement](verification/game-compatibility/DOOM-0317-single-shot-audio.md) are engineering evidence, not material to include in the public handoff.

Do not share the entire `deliverables` directory; it also contains old personal
and diagnostic builds. To verify the isolated share folder without modifying it,
run `.\tools\Test-ReleaseRegression.ps1 -Release 0.3.17`. See the
[handoff verification instructions](tools/Export-Public0317.md). The
[earlier 64-bit release](public-distribution/0.3.16/) remains available.

The public build contains a FreeDOS C: system disk and a new empty D: data
disk. The application copies these seeds into private writable storage on
first launch. Do not uninstall or clear data to update. Android 8.0–8.1
cannot update the published 0.3.15 in place because that release presented a
different certificate to those Android versions. Keep the old installation if
its disks matter. See the [installation notes](public-distribution/0.3.17/README.txt).

The signed 0.3.16 APK completed a FreeDOS command and lifecycle/orientation
test on native x86-64 Android 17 with 16 KiB pages. That is not an ARM64
hardware, Samsung, Android 5, or game-performance result for these APK bytes.
See the [runtime verification record](verification/RELEASE-AUDIT-0.3.16-public.md).

## Runtime features

- 64 MB Pentium III-class x86 guest using QEMU TCG
- Sound Blaster 16, AdLib, and PC speaker audio at 44.1 kHz stereo
- precise VGA retrace for Mode X software
- Android soft keyboard and physical keyboard support
- Android/DOS clipboard bridge and host IME byte bridge
- portrait, landscape, and IME resize support
- background pause/resume and graceful task-removal shutdown
- QEMU user-mode networking with the active Android DNS server

## Build

The Android project uses JDK 17, Android SDK 35, Android Gradle Plugin 8.7.3,
and Gradle 8.14. To build a **staging candidate**, use the following command.
It writes to a fresh folder under `build/public-release-staging`, not to the
published release. Building a candidate is unnecessary for installation.

```powershell
Set-Location -LiteralPath 'D:\Projects\Android-FreeDOS'
.\tools\Build-UniversalPublicCandidate.ps1
```

Run `.\tools\Test-ReleaseRegression.ps1` for the current 0.3.17 offline
regression. It checks the signed four-ABI APK, all 36 native libraries and
their 16 KiB alignment, the public disk seeds, both source archives, the
final APK's native x86 Android 6 DOS command test, and the offline unit tests.
Use `.\tools\Test-ReleaseRegression.ps1 -EvidenceOnly` for the shorter check:
it hashes the shipped APK, engine ZIP and original runtime JSON, and verifies
the actual VER echo, FreeCOM response and returned prompt. It writes a local
report without contacting Android. Neither mode asserts that all games ran.
Historical checks remain available with `-Release 0.3.16` (including its own
Android 17 lifecycle evidence) or `-Release 0.3.15`. Passing an old signed
APK via `-Apk` also selects its matching checks by its exact hash.

## Source companions

- The share folder contains `FreeDOS-Pocket-0.3.17-engine-source.zip`:
  the current Java integration, native sources, pinned upstream inputs,
  patches and all four ABI recipes. `source-companion\engine` is the older source
  baseline, not this release archive.
- `FreeDOS-Pocket-0.3.14-FreeDOS-source.zip` remains the unchanged guest
  source companion in the same share folder.

Detailed evidence is stored under `verification`.

## Local add-on archive

Content not preinstalled in the public build is stored separately at:

`C:\Users\19355\Desktop\FreeDOS-Pocket-Addons`

That folder contains the original VDI files, extracted D: content, package
archives, and a per-file hash inventory. It is not part of the public APK.
