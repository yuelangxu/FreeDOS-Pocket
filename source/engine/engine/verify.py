#!/usr/bin/env python3
"""Verify the FreeDOS Pocket engine source companion and its patches."""

from __future__ import annotations

import argparse
import hashlib
import inspect
import json
import re
import subprocess
import tarfile
import tempfile
from pathlib import Path


PATCH_COMPONENTS = {
    "qemu-5.1.0-freedos-pocket.patch": ("qemu-5.1.0.tar.xz", "qemu-5.1.0"),
    "SDL2-2.0.8-freedos-pocket.patch": ("SDL2-2.0.8.tar.gz", "SDL2-2.0.8"),
    "glib-2.56.1-freedos-pocket.patch": ("glib-2.56.1.tar.xz", "glib-2.56.1"),
    "pixman-0.40.0-freedos-pocket.patch": ("pixman-0.40.0.tar.gz", "pixman-0.40.0"),
    "libffi-3.3-freedos-pocket.patch": ("libffi-3.3.tar.gz", "libffi-3.3"),
}

FORBIDDEN_TEXT = (
    "D:\\Virtualization",
    "C:\\Users\\19355",
    "Retro Workstation",
    "Created by Codex",
    "Made by Codex",
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def normalized_sha256(path: Path) -> str:
    data = path.read_bytes().replace(b"\r\n", b"\n").replace(b"\r", b"\n")
    return hashlib.sha256(data).hexdigest()


def safe_extract(archive: Path, destination: Path) -> None:
    root = destination.resolve()
    with tarfile.open(archive, "r:*") as package:
        for member in package.getmembers():
            resolved = (destination / member.name).resolve()
            if root != resolved and root not in resolved.parents:
                raise ValueError(f"Unsafe archive member: {member.name}")
        if "filter" in inspect.signature(package.extractall).parameters:
            package.extractall(destination, filter="fully_trusted")
        else:
            package.extractall(destination)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--companion", type=Path)
    args = parser.parse_args()

    if args.companion:
        root = args.companion.resolve()
    elif Path(__file__).resolve().parent.joinpath("manifest.json").is_file():
        root = Path(__file__).resolve().parent
    else:
        root = Path(__file__).resolve().parents[1] / "source-companion" / "engine"

    manifest_path = root / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("schema_version") != 1:
        raise ValueError("Unsupported or missing companion schema version")

    records = {record["path"]: record for record in manifest["files"]}
    actual = {
        path.relative_to(root).as_posix()
        for path in root.rglob("*")
        if path.is_file() and path != manifest_path
    }
    expected = set(records)
    if actual != expected:
        raise ValueError(
            f"Companion file set mismatch; missing={sorted(expected-actual)}, unexpected={sorted(actual-expected)}"
        )

    for relative, record in records.items():
        path = root / relative
        if path.stat().st_size != record["bytes"] or sha256(path) != record["sha256"]:
            raise ValueError(f"File does not match manifest: {relative}")

    if len(records) != manifest["file_count_excluding_manifest"]:
        raise ValueError("Manifest file count is inconsistent")
    if sum(record["bytes"] for record in records.values()) != manifest["bytes_excluding_manifest"]:
        raise ValueError("Manifest byte total is inconsistent")

    forbidden_suffixes = {".jks", ".keystore", ".vdi", ".so", ".a", ".o"}
    forbidden_parts = {"jnilibs", "obj", "libs", ".gradle"}
    for relative in actual:
        path = Path(relative)
        lowered_parts = {part.lower() for part in path.parts}
        if path.suffix.lower() in forbidden_suffixes or lowered_parts & forbidden_parts:
            raise ValueError(f"Forbidden generated/private artifact in source companion: {relative}")
        if path.name.lower() == "local.properties":
            raise ValueError(f"Machine-specific file in source companion: {relative}")

    scan_paths = [
        path
        for path in root.rglob("*")
        if path.is_file()
        and path.name != "verify.py"
        and "upstream" not in path.relative_to(root).parts
    ]
    scan_paths.append(manifest_path)
    for path in scan_paths:
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for term in FORBIDDEN_TEXT:
            if term.lower() in text.lower():
                raise ValueError(f"Forbidden personal/development wording in {path.relative_to(root)}: {term}")
        if re.search(r"[A-Za-z]:\\Users\\[^\\\s]+", text):
            raise ValueError(f"Absolute user profile path in {path.relative_to(root)}")

    archive_records = {record["name"]: record for record in manifest["upstream_archives"]}
    for name, record in archive_records.items():
        archive = root / "upstream" / name
        if archive.stat().st_size != record["bytes"] or sha256(archive) != record["sha256"]:
            raise ValueError(f"Upstream archive mismatch: {name}")

    patch_records = {record["name"]: record for record in manifest["patches"]}
    if set(patch_records) != set(PATCH_COMPONENTS):
        raise ValueError("Patch set is incomplete or unexpected")

    patch_results = []
    for patch_name, (archive_name, extracted_name) in PATCH_COMPONENTS.items():
        record = patch_records[patch_name]
        patch = root / "patches" / patch_name
        if patch.stat().st_size != record["bytes"] or sha256(patch) != record["sha256"]:
            raise ValueError(f"Patch mismatch: {patch_name}")
        with tempfile.TemporaryDirectory(prefix="freedos-pocket-source-verify-") as temporary:
            extract_root = Path(temporary)
            safe_extract(root / "upstream" / archive_name, extract_root)
            source_root = extract_root / extracted_name
            check = subprocess.run(
                ["git", "apply", "--whitespace=nowarn", "--check", str(patch)],
                cwd=source_root,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                check=False,
            )
            if check.returncode:
                raise RuntimeError(f"Patch check failed for {patch_name}:\n{check.stdout}")
            subprocess.run(
                ["git", "apply", "--whitespace=nowarn", str(patch)],
                cwd=source_root,
                check=True,
            )
            for changed in record["changed_files"]:
                path = source_root / changed["path"]
                if normalized_sha256(path) != changed["normalized_sha256"]:
                    raise ValueError(f"Patched source hash mismatch: {patch_name}: {changed['path']}")
            patch_results.append({"patch": patch_name, "verified_files": len(record["changed_files"])})

    required = (
        "project/native-jni/Android.mk",
        "project/native-jni/Application.mk",
        "project/native-jni/Makefile",
        "project/android-app/app/AndroidManifest.xml",
        "project/android-app/app/build.gradle",
        "project/guest-tools/hostime/HOSTIME.ASM",
        "rebuild/prepare-native-tree.py",
        "licenses/Limbo-GPL-2.0.txt",
        "licenses/HOSTIME-MIT.txt",
    )
    for relative in required:
        if relative not in records:
            raise ValueError(f"Required source/build input missing: {relative}")

    print(
        json.dumps(
            {
                "result": "PASS",
                "companion": str(root),
                "files": len(records),
                "bytes": manifest["bytes_excluding_manifest"],
                "upstream_archives": len(archive_records),
                "patches": patch_results,
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
