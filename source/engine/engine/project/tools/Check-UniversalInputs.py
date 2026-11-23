"""Bind the four-ABI public candidate to the accepted native inputs and previous public assets."""
import argparse
import hashlib
import json
import re
import runpy
import shutil
import sys
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ELF = runpy.run_path(str(ROOT / "tools/Verify-PackagedAbi.py"))
LIBS = ELF["REQUIRED"]
ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
PREVIOUS = ROOT / "public-distribution/0.3.16"
OLD_APK = PREVIOUS / "FreeDOS-Pocket-0.3.16-public-release.apk"
OLD_INPUTS_HASH = "0561e2a3c3ee00f558850b6578cfbd0103484d2572c7fc20248829de8c0403f7"
OLD_APK_HASH = "546b9bb7af304b084a1b401e69645fcf265ddb539d39f7ad598ba7781ddc281a"


def require(ok, text):
    if not ok:
        raise ValueError(text)


def file_hash(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def inputs():
    with zipfile.ZipFile(PREVIOUS / "FreeDOS-Pocket-0.3.16-engine-source.zip") as old:
        raw = old.read("engine/native-inputs.json")
    require(hashlib.sha256(raw).hexdigest() == OLD_INPUTS_HASH, "Previous release native map not recognized.")
    old_hash = json.loads(raw)["libraries"]
    result = {}
    for abi in ABIS:
        if abi == "arm64-v8a":
            src = ROOT / "app/jniLibs" / abi
            expected = old_hash[abi]
        else:
            src = ROOT / "build/compat-probe-native" / abi / abi
            audit = ROOT / "build" / ("compat-" + abi) / "native-probe-audit.json"
            require(audit.is_file(), f"Not ready: missing completed native audit for {abi}.")
            r = json.loads(audit.read_text(encoding="utf-8"))
            require(r["abi"] == abi and r["status"] == "static-check-pass-not-a-boot-test"
                    and set(r["libraries"]) == LIBS, f"No passing 9/9 native audit for {abi}.")
            expected = {}
            for n, v in r["libraries"].items():
                require(v["platform_stubs_api"] == (26 if n == "libcompat-SDL2-addons.so" else 21)
                        and v["min_load_alignment"] >= 16384 and re.fullmatch("[a-f0-9]{64}", v["sha256"]),
                        f"Incomplete API/alignment/hash evidence: {abi}/{n}")
                expected[n] = v["sha256"]
            if abi == "x86_64":
                require(expected == old_hash[abi], "Changed x86-64 input from 0.3.16.")
        require(src.resolve() == src and src.is_dir() and {p.name for p in src.iterdir()} == LIBS and
                set(expected) == LIBS, f"Unexpected directory or missing/extraneous library: {abi}.")
        for n in sorted(LIBS):
            p = src / n
            require(p.is_file() and p.resolve() == p and 0 < p.stat().st_size <= 64 * 1024**2,
                    f"Invalid library: {p}")
            data = p.read_bytes()
            require(hashlib.sha256(data).hexdigest() == expected[n], f"Audited bytes changed: {abi}/{n}")
            ELF["inspect_elf"](data, abi)
            result[f"lib/{abi}/{n}"] = p, expected[n]
    return result


def stage(mapping, dest):
    dest = dest.absolute()
    require(dest.resolve() == dest and dest.parent.parent == ROOT / "build/public-release-staging"
            and re.fullmatch(r"0317-[a-f0-9]{32}", dest.parent.name) and dest.name == "verified-jni"
            and dest.parent.is_dir() and not dest.exists(), "Use a new private build staging directory.")
    dest.mkdir()
    for member,(src,digest) in mapping.items():
        _, abi, n = member.split("/")
        out = dest / abi / n
        out.parent.mkdir(exist_ok=True)
        with src.open("rb") as inp, out.open("xb") as target:
            shutil.copyfileobj(inp, target, 1024**2)
        require(file_hash(out) == digest, f"Staged bytes changed: {abi}/{n}")


def verify(mapping, apk):
    require(file_hash(OLD_APK) == OLD_APK_HASH, "Previous public reference APK is not the pinned release.")
    with zipfile.ZipFile(apk) as current, zipfile.ZipFile(OLD_APK) as old:
        names = current.namelist()
        require(len(names) == len(set(names)) and set(n for n in names if n.startswith("lib/") and not n.endswith("/"))
                == mapping.keys(), "Duplicate APK entries or unexpected native set.")
        assets = sorted(i.filename for i in old.infolist() if i.filename.startswith("assets/") and not i.is_dir())
        require(assets and assets == sorted(n for n in names if n.startswith("assets/") and not n.endswith("/")),
                "Public assets differ in name or number from 0.3.16.")
        for n in assets:
            with old.open(n) as a, current.open(n) as b:
                require(hashlib.file_digest(a, "sha256").digest() == hashlib.file_digest(b, "sha256").digest(),
                        "Unexpected public asset content: " + n)
        for n, (_, digest) in mapping.items():
            with current.open(n) as b:
                require(hashlib.file_digest(b, "sha256").hexdigest() == digest, "Wrong packaged library: " + n)
    ELF["verify"](apk, ABIS)


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    group = cli.add_mutually_exclusive_group()
    group.add_argument("--stage", type=Path)
    group.add_argument("--verify-apk", type=Path)
    args = cli.parse_args()
    try:
        binding = inputs()
        if args.stage:
            stage(binding, args.stage)
        if args.verify_apk:
            verify(binding, args.verify_apk)
        print("PASS: all 36 native input fingerprints and ELF ABI/alignment." +
              (" Packaged libraries and public assets exactly match." if args.verify_apk else ""))
    except (ValueError, KeyError, OSError, zipfile.BadZipFile) as exc:
        sys.exit(f"Candidate gate failed: {exc}")
