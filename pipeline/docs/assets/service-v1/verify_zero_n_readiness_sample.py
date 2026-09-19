#!/usr/bin/env python3
"""Verify the deterministic 0-N readiness sample without model-specific code."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import defaultdict
from pathlib import Path


def read_jsonl(path: Path) -> list[dict]:
    with path.open("r", encoding="utf-8") as stream:
        return [json.loads(line) for line in stream if line.strip()]


def rows_digest(rows: list[dict]) -> str:
    payload = "\n".join(json.dumps(row, sort_keys=True, separators=(",", ":")) for row in rows).encode()
    return hashlib.sha256(payload).hexdigest()


def file_digest(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def verify(root: Path) -> dict:
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    report = json.loads((root / "split-distribution-report.json").read_text(encoding="utf-8"))
    episodes = read_jsonl(root / "episodes.jsonl")
    candidates = read_jsonl(root / "candidates.jsonl")
    errors: list[str] = []

    if len(episodes) != manifest["episodes"]["rows"] or rows_digest(episodes) != manifest["episodes"]["sha256"]:
        errors.append("episodes row count or digest mismatch")
    if len(candidates) != manifest["candidates"]["rows"] or rows_digest(candidates) != manifest["candidates"]["sha256"]:
        errors.append("candidates row count or digest mismatch")
    if file_digest(root / "split-distribution-report.json") != manifest["distribution_report"]["sha256"]:
        errors.append("distribution report digest mismatch")

    user_roles: dict[int, set[str]] = defaultdict(set)
    episode_identity: dict[tuple[str, int], tuple[int, int, str]] = {}
    seen_n: set[tuple[str, int, int]] = set()
    for row in episodes:
        key = (row["role"], row["uid"])
        user_roles[row["uid"]].add(row["role"])
        identity = (row["target_movie_id"], row["prediction_at"], row["candidate_digest"])
        if key in episode_identity and episode_identity[key] != identity:
            errors.append(f"episode identity changed across N: {key}")
        episode_identity[key] = identity
        n_key = (*key, row["n"])
        if n_key in seen_n:
            errors.append(f"duplicate role/user/N: {n_key}")
        seen_n.add(n_key)
        history = row["history"]
        if len(history) != row["n"]:
            errors.append(f"history length mismatch: {n_key}")
        if row["supported_history_count"] > row["n"] or row["n"] > row["total_history_count"]:
            errors.append(f"history count invariant failed: {n_key}")
        if any(event[0] == row["target_movie_id"] or event[2] >= row["prediction_at"] for event in history):
            errors.append(f"target leakage or future history: {n_key}")
    if any(len(roles) != 1 for roles in user_roles.values()):
        errors.append("a user appears in multiple roles")

    candidate_groups: dict[tuple[str, int], list[dict]] = defaultdict(list)
    for row in candidates:
        candidate_groups[(row["role"], row["uid"])].append(row)
    for key, rows in candidate_groups.items():
        rows.sort(key=lambda row: row["candidate_rank"])
        positives = [row for row in rows if row["label_state"] == "POSITIVE_OBSERVED"]
        if len(positives) != 1 or positives[0]["candidate_movie_id"] != positives[0]["target_movie_id"]:
            errors.append(f"candidate observed-label invariant failed: {key}")
        digest = hashlib.sha256(",".join(str(row["candidate_movie_id"]) for row in rows).encode()).hexdigest()
        if key not in episode_identity or digest != episode_identity[key][2]:
            errors.append(f"candidate digest mismatch: {key}")

    if report["insufficient_cells"]:
        errors.append("required role/N cells are insufficient")
    if errors:
        raise ValueError("; ".join(errors[:20]))
    return {
        "status": "PASS",
        "users": len(user_roles),
        "episodes": len(episodes),
        "candidates": len(candidates),
        "distribution_status": report["status"],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    print(json.dumps(verify(parser.parse_args().root), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
