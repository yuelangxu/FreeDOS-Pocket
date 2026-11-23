# FreeDOS Pocket

FreeDOS Pocket is an offline Android application that runs an x86 FreeDOS
environment through an embedded Limbo/QEMU engine. It does not require root,
Termux, an internet connection, or a browser runtime.

## Current release

Version **0.3.17-public** (version code 21) targets Android 35 and supports
Android API 21 or later. One APK contains native engines for:

- `armeabi-v7a`
- `arm64-v8a`
- `x86`
- `x86_64`

The public package creates its own FreeDOS C: system disk and an empty D: data
disk. It does not include personal games, documents, add-ons, or saves.

Download the APK from the [0.3.17 GitHub Release](../../releases/tag/v0.3.17).
The release also contains the corresponding engine source and unchanged
FreeDOS guest-source archive. The ZIP files are source/distribution materials,
not additional installers.

The application integration and patch files are browsable in the [`source/`](source/)
directory. The complete upstream source collections remain attached to the
release because GitHub's normal Git object limit does not allow some of their
individual archive files; see [SOURCE-LAYOUT.md](SOURCE-LAYOUT.md).

## Checksums

```text
e6e2545cfb1dd4ac13da484d75a32746ce5a1ec1b093ab1c2b9ea934b097fdef  FreeDOS-Pocket-0.3.17-public-release.apk
675b211aaadf02e395a28f9dcd863403608939101e016cfc4e867fbf9961c5c1  FreeDOS-Pocket-0.3.17-engine-source.zip
83d5245422b43eb599bc47642d035a837dcd9b7d0a1c52fe0315f16d07e91f1a  FreeDOS-Pocket-0.3.14-FreeDOS-source.zip
```

The APK was tested on Android 6 native 32-bit x86, Android 17 x86-64 with
16 KiB pages, and a Redmi Pad SE running Android 14 ARM64. These checks do not
promise compatibility with every phone, tablet, Android version, or game.

## Updating

Install the new APK over an existing public installation. Do **not** uninstall
the old app or clear its storage, because those actions erase the private DOS
disks. Android 8.0/8.1 may reject an update from older 0.3.15 installations
with a different historical signing identity.

## License and source

QEMU is GPLv2-licensed; other bundled components retain their upstream
licenses. The corresponding source archives are attached to the release so
they can be downloaded from the same place as the APK. See the notices and
build instructions inside the archives.

This repository intentionally does not store the large APK or disk images in
Git history. They are attached to the release instead.
