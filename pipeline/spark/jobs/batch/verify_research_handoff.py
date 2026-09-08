"""Verify copied evidence; optionally verify external files against the manifest."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def verify(root: Path, relative: str, expected: dict) -> None:
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()) or not path.is_file():
        raise ValueError(f"missing file or path outside root: {relative}")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    if path.stat().st_size != expected["bytes"] or digest.hexdigest() != expected["sha256"]:
        raise ValueError(f"size/hash mismatch: {relative}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, help="external research snapshot root")
    args = parser.parse_args()
    pipeline = Path(__file__).resolve().parents[3]
    repository = pipeline.parent
    provenance = json.loads((pipeline / "docs/assets/provenance.json").read_text("utf-8"))
    copied = [entry for entry in provenance["source_files"] if "team_path" in entry]
    for entry in copied:
        verify(repository, entry["team_path"], entry)
    print(f"PASS: {len(copied)} imported evidence files match recorded hashes")
    if args.source_root is not None:
        count = 0
        for manifest in sorted((pipeline / "artifacts").glob("*.json")):
            for entry in json.loads(manifest.read_text("utf-8"))["files"]:
                verify(args.source_root, entry["source_path"], entry)
                count += 1
        print(f"PASS: {count} external manifest files match; no shared upload implied")
    else:
        print("External artifacts not checked; shared locations remain unverified")


if __name__ == "__main__":
    main()
