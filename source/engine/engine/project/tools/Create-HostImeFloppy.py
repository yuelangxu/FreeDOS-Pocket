#!/usr/bin/env python3
"""Create a deterministic 1.44 MB FAT12 image containing HOSTIME.COM."""
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[1]
source = ROOT / "guest-tools" / "hostime" / "HOSTIME.COM"
target = ROOT / "guest-tools" / "hostime" / "HOSTIME.IMG"
payload = source.read_bytes()
if not payload or len(payload) > 512:
    raise SystemExit("HOSTIME.COM must fit in one FAT12 cluster")

image = bytearray(1_474_560)
boot = memoryview(image)[:512]
boot[0:3] = b"\xeb\x3c\x90"
boot[3:11] = b"FDPOCKET"
struct.pack_into("<HBHBHHBHHHII", boot, 11,
                 512, 1, 1, 2, 224, 2880, 0xF0, 9, 18, 2, 0, 0)
boot[36:62] = b"\x00\x00\x29" + struct.pack("<I", 0x20261120) + b"HOSTIME    " + b"FAT12   "
boot[62:62+len(b"FreeDOS Pocket host text bridge\r\n")] = b"FreeDOS Pocket host text bridge\r\n"
boot[510:512] = b"\x55\xaa"

def set_fat12(fat: bytearray, cluster: int, value: int) -> None:
    offset = cluster + cluster // 2
    if cluster & 1:
        fat[offset] = (fat[offset] & 0x0F) | ((value << 4) & 0xF0)
        fat[offset + 1] = (value >> 4) & 0xFF
    else:
        fat[offset] = value & 0xFF
        fat[offset + 1] = (fat[offset + 1] & 0xF0) | ((value >> 8) & 0x0F)

fat = bytearray(9 * 512)
fat[0:3] = b"\xf0\xff\xff"
set_fat12(fat, 2, 0xFFF)
image[512:512+len(fat)] = fat
image[10*512:10*512+len(fat)] = fat

root_offset = 19 * 512
entry = bytearray(32)
entry[0:11] = b"HOSTIME COM"
entry[11] = 0x20
# Stable 2026-11-20 00:00 local FAT timestamp.
struct.pack_into("<HH", entry, 22, 0, ((2026-1980) << 9) | (11 << 5) | 20)
struct.pack_into("<H", entry, 26, 2)
struct.pack_into("<I", entry, 28, len(payload))
image[root_offset:root_offset+32] = entry
data_offset = 33 * 512
image[data_offset:data_offset+len(payload)] = payload

target.write_bytes(image)
print(f"Created {target} ({len(image)} bytes, HOSTIME.COM {len(payload)} bytes)")
