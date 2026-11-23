# FreeDOS Pocket

FreeDOS Pocket is an offline Android application that runs an x86 FreeDOS
environment through an embedded Limbo/QEMU engine. It does not require root,
Termux, an internet connection, or a browser runtime.

## Current release

Version **0.3.19** (version code 23) targets Android 35 and supports Android
API 21 or later. One APK contains native engines for:

- `armeabi-v7a`
- `arm64-v8a`
- `x86`
- `x86_64`

The package carries a preconfigured FreeDOS system disk and software disk for
offline use.

Download the APK from the [0.3.19 GitHub Release](../../releases/tag/v0.3.19).
The release also contains its corresponding engine and Android source. The
unchanged FreeDOS guest-source collection remains available from the
[0.3.17 GitHub Release](../../releases/tag/v0.3.17). Source ZIP files are
distribution materials, not additional installers.

The application integration and patch files are browsable in the [`source/`](source/)
directory. The complete upstream source collections remain attached to the
release because GitHub's normal Git object limit does not allow some of their
individual archive files; see [SOURCE-LAYOUT.md](SOURCE-LAYOUT.md).

## Checksums

```text
0d42f1c2e411e101f8b5e4ccf645298788053ced2e1245ee97d01da81f0817f3  FreeDOS-Pocket-0.3.19.apk
3485592ac7784cb4a4c73fa98f7065f4b40e026d784dc3792b8edc9848842a23  FreeDOS-Pocket-0.3.19-engine-source.zip
```

The four native engine architectures are `armeabi-v7a`, `arm64-v8a`, `x86`,
and `x86_64`. Hardware, Android builds, and individual DOS programs can still
behave differently.

## Updating

Install the new APK over an existing installation. Do **not** uninstall the
old app or clear its storage if you want to retain the existing DOS disks.

## License and source

QEMU is GPLv2-licensed; other bundled components retain their upstream
licenses. The corresponding 0.3.19 engine source archive is attached to the
release. See the notices and build instructions inside it.

This repository intentionally does not store the large APK or disk images in
Git history. They are attached to the release instead.
