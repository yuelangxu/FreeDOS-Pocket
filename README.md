# FreeDOS Pocket

FreeDOS Pocket is an offline Android application that runs an x86 FreeDOS
environment through an embedded Limbo/QEMU engine. It does not require root,
Termux, an internet connection, or a browser runtime.

## Current release

Version **0.3.20** (version code 24) targets Android 35 and supports Android
API 21 or later. One APK contains native engines for:

- `armeabi-v7a`
- `arm64-v8a`
- `x86`
- `x86_64`

The package carries a preconfigured FreeDOS system disk and software disk for
offline use.

Download the APK from the [0.3.20 GitHub Release](../../releases/tag/v0.3.20).
The release also contains its corresponding engine and Android source. The
unchanged FreeDOS guest-source collection remains available from the
[0.3.17 GitHub Release](../../releases/tag/v0.3.17). Source ZIP files are
distribution materials, not additional installers.

The application integration and patch files are browsable in the [`source/`](source/)
directory. The complete upstream source collections remain attached to the
release because GitHub's normal Git object limit does not allow some of their
individual archive files; see [SOURCE-LAYOUT.md](SOURCE-LAYOUT.md).

This release fixes an ARM64 native startup crash and repairs stale
`C:\DEVEL\` paths in bundled DOS launchers. See [compatibility checks](COMPATIBILITY.md).

## Updating

Install the new APK over an existing installation. Do **not** uninstall the
old app or clear its storage if you want to retain the existing DOS disks.
Corrected launchers ship in new seed disks; existing disks are not overwritten.

## License and source

QEMU is GPLv2-licensed; other bundled components retain their upstream
licenses. The corresponding 0.3.20 engine source archive is attached to the
release. See the notices and build instructions inside it.

This repository intentionally does not store the large APK or disk images in
Git history. They are attached to the release instead.
