#!/usr/bin/env python3
"""Remove optional CD-ROM probing from a FreeDOS system RAW disk."""

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "verification" / "tools" / "python-deps"))
from pyfatfs.PyFatFS import PyFatFS  # noqa: E402


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("disk", type=Path)
    args = parser.parse_args()
    disk = args.disk.resolve()
    fs = PyFatFS(str(disk), offset=32256, read_only=False)
    try:
        path = "/FDAUTO.BAT"
        original = fs.getbytes(path)
        text = original.decode("latin-1")
        blocks = (
            "if not exist %DOSDIR%\\BIN\\CDROM.BAT goto NOCDROM\r\n"
            "call %DOSDIR%\\BIN\\CDROM.BAT\r\n"
            "REM - Add other stuff here once CDROM is loaded..\r\n"
            ":NOCDROM\r\n",
            "REM - Display drive for CD-ROM\r\n"
            "if not exist %DOSDIR%\\BIN\\CDROM.BAT goto NOCDSTATUS\r\n"
            "echo.\r\n"
            "call %DOSDIR%\\BIN\\CDROM.BAT status\r\n"
            ":NOCDSTATUS\r\n",
        )
        for block in blocks:
            if block not in text:
                raise ValueError("Expected CD-ROM boot block was not found")
            text = text.replace(block, "", 1)
        if "CDROM.BAT" in text:
            raise ValueError("CD-ROM reference remains after edit")
        fs.setbytes(path, text.encode("latin-1"))
        if b"CDROM.BAT" in fs.getbytes(path):
            raise IOError("CD-ROM boot edit verification failed")
    finally:
        fs.close()


if __name__ == "__main__":
    main()
