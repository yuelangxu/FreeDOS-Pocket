# HOSTIME.COM

Tiny FreeDOS resident receiver for the Android host-text bridge. It polls
COM1 from INT 1Ch, inserts accepted bytes into the BIOS keyboard ring, and
echoes each byte to acknowledge delivery. This avoids pretending Unicode can
be represented by virtual PC key scan codes.

Build with the project-pinned NASM:

`../../toolchain/nasm-3.02/nasm.exe -f bin -o HOSTIME.COM HOSTIME.ASM`

Run once in the guest: `HOSTIME`. Reboot to unload. It uses COM1 at 115200
8N1. A compatible Chinese DOS display/input environment is still required to
render GB2312; this program only transports bytes. Do not install twice: the
signature check prevents duplicate hooks.

Security: QEMU exposes the serial socket only on Android loopback. The Android
client must send one byte and wait for the identical acknowledgement before
sending another, preventing the 16550/BIOS buffers from being overrun.

License: MIT. See `LICENSE`.
