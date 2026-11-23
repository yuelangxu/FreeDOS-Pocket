#!/usr/bin/env python3
"""Prepare the patched FreeDOS Pocket native JNI source tree offline."""

from __future__ import annotations

import argparse
import hashlib
import inspect
import json
import shutil
import subprocess
import tarfile
import tempfile
from pathlib import Path


COMPONENTS = {
    "qemu": ("qemu-5.1.0.tar.xz", "qemu-5.1.0", "qemu-5.1.0-freedos-pocket.patch"),
    "SDL2": ("SDL2-2.0.8.tar.gz", "SDL2-2.0.8", "SDL2-2.0.8-freedos-pocket.patch"),
    "glib": ("glib-2.56.1.tar.xz", "glib-2.56.1", "glib-2.56.1-freedos-pocket.patch"),
    "pixman": ("pixman-0.40.0.tar.gz", "pixman-0.40.0", "pixman-0.40.0-freedos-pocket.patch"),
    "libffi": ("libffi-3.3.tar.gz", "libffi-3.3", "libffi-3.3-freedos-pocket.patch"),
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


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
    parser.add_argument(
        "--companion-root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="Engine source-companion root (defaults to the parent of rebuild/).",
    )
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()

    root = args.companion_root.resolve()
    output = args.output.resolve()
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    archive_records = {record["name"]: record for record in manifest["upstream_archives"]}

    if output.exists():
        if not args.force:
            raise SystemExit(f"Output already exists: {output} (use --force to replace it)")
        if output == Path(output.anchor) or len(output.parts) < 3:
            raise ValueError(f"Refusing unsafe output removal: {output}")
        shutil.rmtree(output)

    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="freedos-pocket-native-", dir=output.parent) as temporary:
        stage = Path(temporary) / "native-jni"
        shutil.copytree(root / "project" / "native-jni", stage)

        for target_name, (archive_name, extracted_name, patch_name) in COMPONENTS.items():
            archive = root / "upstream" / archive_name
            record = archive_records[archive_name]
            if archive.stat().st_size != record["bytes"] or sha256(archive) != record["sha256"]:
                raise ValueError(f"Archive does not match manifest: {archive_name}")

            extract_root = Path(temporary) / f"extract-{target_name}"
            extract_root.mkdir()
            safe_extract(archive, extract_root)
            extracted = extract_root / extracted_name
            if not extracted.is_dir():
                raise ValueError(f"Expected archive root missing: {extracted_name}")
            shutil.move(str(extracted), str(stage / target_name))

            patch = root / "patches" / patch_name
            process = subprocess.run(
                ["git", "apply", "--whitespace=nowarn", "--check", str(patch)],
                cwd=stage / target_name,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                check=False,
            )
            if process.returncode:
                raise RuntimeError(f"Patch check failed for {target_name}:\n{process.stdout}")
            subprocess.run(
                ["git", "apply", "--whitespace=nowarn", str(patch)],
                cwd=stage / target_name,
                check=True,
            )

        shutil.move(str(stage), str(output))

    print(json.dumps({"result": "PASS", "native_tree": str(output)}, indent=2))


if __name__ == "__main__":
    main()
