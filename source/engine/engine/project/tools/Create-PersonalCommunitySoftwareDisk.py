#!/usr/bin/env python3
"""Create the private D: software disk with one unified DEVEL directory.

The source tree is the curated Content directory plus the FreeDOS C:\\DEVEL
tree extracted into Content\\DEVEL.  Download caches and VDI backups are never
read by this tool.
"""

from __future__ import annotations

import argparse
import struct
import sys
from pathlib import Path
from pathlib import PurePosixPath


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "verification" / "tools" / "python-deps"))
from pyfatfs.PyFat import PyFat  # noqa: E402
from pyfatfs.PyFatFS import PyFatFS  # noqa: E402


SECTOR = 512
START_LBA = 2048
VOLUME_ID = 0x534F4654  # "SOFT"


def write_mbr(image: Path, sectors: int) -> int:
    part_sectors = sectors - START_LBA
    if part_sectors <= 66600:
        raise ValueError("Disk is too small for FAT32")
    mbr = bytearray(SECTOR)
    mbr[446:462] = struct.pack(
        "<B3sB3sII", 0, bytes((0x20, 0x21, 0)), 0x0C,
        bytes((0xFE, 0xFF, 0xFF)), START_LBA, part_sectors,
    )
    mbr[510:512] = b"\x55\xaa"
    with image.open("r+b") as stream:
        stream.write(mbr)
    return part_sectors


def patch_geometry(image: Path, offset: int) -> None:
    with image.open("r+b") as stream:
        for boot in (offset, offset + 6 * SECTOR):
            stream.seek(boot + 24)
            stream.write(struct.pack("<HHI", 63, 255, START_LBA))


def copy_tree(fs: PyFatFS, source: Path) -> None:
    for entry in sorted(source.rglob("*"), key=lambda item: (len(item.parts), item.name.upper())):
        relative = entry.relative_to(source).as_posix()
        target = "/" + relative
        if entry.is_dir():
            fs.makedir(target, recreate=True)
        elif entry.is_file():
            fs.setbytes(target, entry.read_bytes())


def move_system_devel(target: PyFatFS, system_raw: Path) -> None:
    """Copy C:\\DEVEL into D:\\DEVEL, then remove the source only on success."""
    source = PyFatFS(str(system_raw), offset=32256, read_only=True)
    try:
        if not source.exists("/DEVEL"):
            raise IOError("The source system disk has no C:\\DEVEL tree")
        for step in source.walk("/DEVEL"):
            suffix = PurePosixPath(step.path).relative_to("/DEVEL")
            target_root = PurePosixPath("/DEVEL") / suffix
            target.makedir(str(target_root), recreate=True)
            for directory in step.dirs:
                target.makedir(str(target_root / directory.name), recreate=True)
            for filename in step.files:
                source_name = str(PurePosixPath(step.path) / filename.name)
                target.setbytes(str(target_root / filename.name), source.getbytes(source_name))
    finally:
        source.close()

    for required in ("/DEVEL/ASMLAB", "/DEVEL/MASM500", "/DEVEL/nasm/NASM.EXE"):
        if not target.exists(required):
            raise IOError(f"Required unified development item is missing: {required}")

    source = PyFatFS(str(system_raw), offset=32256, read_only=False)
    try:
        source.removetree("/DEVEL")
        if source.exists("/DEVEL"):
            raise IOError("C:\\DEVEL removal did not complete")
    finally:
        source.close()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--system-raw", type=Path, required=True)
    parser.add_argument("--size-mib", type=int, default=16384)
    args = parser.parse_args()

    source, output, system_raw = args.source.resolve(), args.output.resolve(), args.system_raw.resolve()
    if not source.is_dir() or not (source / "DEVEL").is_dir():
        raise ValueError("Source must contain the merged DEVEL directory")
    if output.exists():
        raise FileExistsError(f"Refusing to overwrite {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    total = args.size_mib * 1024 * 1024
    with output.open("wb") as stream:
        stream.truncate(total)
    offset = START_LBA * SECTOR
    write_mbr(output, total // SECTOR)
    fat = PyFat(offset=offset)
    try:
        fat.mkfs(str(output), PyFat.FAT_TYPE_FAT32, size=total - offset,
                 label="SOFTWARE", volume_id=VOLUME_ID)
    finally:
        fat.close()
    patch_geometry(output, offset)

    fs = PyFatFS(str(output), offset=offset, read_only=False)
    try:
        copy_tree(fs, source)
        move_system_devel(fs, system_raw)
    finally:
        fs.close()


if __name__ == "__main__":
    main()
