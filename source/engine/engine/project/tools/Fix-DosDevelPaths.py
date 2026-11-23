"""Repair old launcher paths on a staged FreeDOS Pocket software disk."""
import re
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / "verification/tools/python-deps"))
from pyfatfs.PyFatFS import PyFatFS


def repair_devel_paths(fs, *, system=False):
    if not system and not fs.exists("/DEVEL/NASM/NASM.EXE"):
        raise ValueError("Not a FreeDOS Pocket D: software disk")
    updated = []
    for step in fs.walk("/"):
        for entry in step.files:
            if not entry.name.upper().endswith(".BAT"):
                continue
            name = step.path.rstrip("/") + "/" + entry.name
            old = fs.getbytes(name)
            new = re.sub(rb"C:(?=\\DEVEL\\)", b"D:", old, flags=re.I)
            if new != old:
                fs.setbytes(name, new)
                if fs.getbytes(name) != new:
                    raise IOError(f"Failed to verify {name}")
                updated.append(name)
    return updated


if __name__ == "__main__":
    disk = PyFatFS(sys.argv[1], offset=2048 * 512, read_only=False)
    try:
        for name in repair_devel_paths(disk):
            print(name)
    finally:
        disk.close()
