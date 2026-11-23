"""Clone the shipped D: disk, repair launchers, and stage APK assets."""
import gzip
import hashlib
import json
from pathlib import Path
import runpy
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
work = root / "release-work/0.3.20/dos"
work.mkdir(parents=True, exist_ok=False)
assets = work / "assets"
seed = assets / "seed"
seed.mkdir(parents=True)
old = root / "app/src/personalCommunityUniversalRelease/assets-nocdrom/seed"
source, raw, fixed = (work / n for n in ["source.vdi", "software.raw", "software.vdi"])
vbox = r"D:\Program Files\Oracle\VirtualBox\VBoxManage.exe"
with gzip.open(old / "software.vdi.bin", "rb") as inp, source.open("xb") as out:
    shutil.copyfileobj(inp, out)
subprocess.run([vbox, "clonemedium", "disk", str(source), str(raw), "--format", "RAW"], check=True)
tool = runpy.run_path(str(root / "tools/Fix-DosDevelPaths.py"))
fs = tool["PyFatFS"](str(raw), offset=2048 * 512, read_only=False)
try:
    changed = tool["repair_devel_paths"](fs)
finally:
    fs.close()
if not changed:
    raise ValueError("No launchers found that needed repair")
print("\n".join(changed), flush=True)
subprocess.run([vbox, "convertfromraw", str(raw), str(fixed), "--format", "VDI"], check=True)
shutil.copyfile(old / "system.vdi.bin", seed / "system.vdi.bin")
with fixed.open("rb") as inp, gzip.open(seed / "software.vdi.bin", "xb", compresslevel=3) as out:
    shutil.copyfileobj(inp, out)
data = json.loads((old / "manifest.json").read_bytes())
with fixed.open("rb") as inp:
    data["software"] = {"bytes": fixed.stat().st_size,
                        "sha256": hashlib.file_digest(inp, "sha256").hexdigest()}
(seed / "manifest.json").write_bytes((json.dumps(data, indent=2) + "\n").encode())
