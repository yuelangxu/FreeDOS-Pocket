"""Repair preinstalled launchers on a copy of the C: boot disk."""
import gzip
import hashlib
import json
from pathlib import Path
import runpy
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
work = root / "release-work/0.3.20/system"
work.mkdir(parents=True, exist_ok=False)
source, raw, fixed = (work / n for n in ("source.vdi", "system.raw", "system.vdi"))
old = root / "app/src/personalCommunityUniversalRelease/assets-nocdrom/seed"
vbox = r"D:\Program Files\Oracle\VirtualBox\VBoxManage.exe"
with gzip.open(old / "system.vdi.bin", "rb") as inp, source.open("xb") as out:
    shutil.copyfileobj(inp, out)
subprocess.run([vbox, "clonemedium", "disk", str(source), str(raw), "--format", "RAW"], check=True)
tool = runpy.run_path(str(root / "tools/Fix-DosDevelPaths.py"))
fs = tool["PyFatFS"](str(raw), offset=32256, read_only=False)
try:
    changed = tool["repair_devel_paths"](fs, system=True)
finally:
    fs.close()
print("\n".join(changed), flush=True)
if not changed:
    shutil.copyfile(old / "system.vdi.bin", work / "system.vdi.bin")
    data = json.loads((old / "manifest.json").read_bytes())["system"]
else:
    subprocess.run([vbox, "convertfromraw", str(raw), str(fixed), "--format", "VDI"], check=True)
    with fixed.open("rb") as inp, gzip.open(work / "system.vdi.bin", "xb", compresslevel=3) as out:
        shutil.copyfileobj(inp, out)
    with fixed.open("rb") as inp:
        data = {"bytes": fixed.stat().st_size, "sha256": hashlib.file_digest(inp, "sha256").hexdigest()}
(work / "manifest.json").write_bytes((json.dumps(data, indent=2) + "\n").encode())
