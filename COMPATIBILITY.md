# Android compatibility checks

Installing an APK or listing four ABIs does not exercise the native emulator.
Before release, verify package alignment and run the guest on representative
devices. Do not treat one successful boot as coverage of all hardware.

- ARM64: test a PAC-capable physical device on a recent Android release,
  and cover both 4 KiB and 16 KiB page sizes where available.
- ARMv7, x86 and x86_64: test natively on the corresponding ABI, rather
  than relying on Android to select a different library.
- On each target, cold boot twice, verify the DOS prompt, exercise guest
  disk I/O and input, and check representative Sound Blaster and MIDI audio.
- For new seeds, test representative preinstalled command launchers and
  keep an existing-installation upgrade test separate from first launch.

`source/engine/engine/project/tools/Test-AndroidDosBoot.py` starts the
installed version via ADB and checks QMP disk reads plus process survival.
Run it with `--serial DEVICE --version VERSION`. Confirm the DOS prompt
separately; a running VM alone does not prove the guest finished booting.

The 0.3.19 ARM64 crash on the Samsung SM-S942B was a native SIGILL in
`_longjmp`, called from QEMU's coroutine backend. The 0.3.20 ARM64 backend
uses a dedicated AAPCS64 context switch, without disabling Android PAC.
See the analogous [vproc report](https://github.com/Bahtya/vproc/issues/29)
and [Android's 16 KB page size guidance](https://developer.android.com/guide/practices/page-sizes).
