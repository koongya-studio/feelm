#!/usr/bin/env python3
"""Build a small, deterministic 0-N readiness sample from MovieLens CSV files."""

from __future__ import annotations

import argparse
import csv
import hashlib
import heapq
import json
import math
import random
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path


ROLES = ("TRAIN", "VALIDATION", "FINAL_TEST")
N_VALUES = (0, 1, 2, 4, 7, 15, 25, 40, 50)
N_BUCKETS = ((0, 0, "0"), (1, 1, "1"), (2, 2, "2"), (3, 4, "3-4"),
             (5, 9, "5-9"), (10, 19, "10-19"), (20, 29, "20-29"),
             (30, 49, "30-49"), (50, 10**12, "50+"))
TRAIN_END = 1577836799
VALIDATION_END = 1640995199
FINAL_TEST_END = 1697164147


@dataclass(frozen=True)
class Rating:
    movie_id: int
    rating: float
    timestamp: int


@dataclass
class UserCase:
    uid: int
    role: str
    target: Rating
    history: list[Rating]
    rank: int


def stable_rank(seed: int, *parts: object) -> int:
    raw = ":".join([str(seed), *(str(part) for part in parts)]).encode()
    return int.from_bytes(hashlib.sha256(raw).digest()[:8], "big")


def role_for_user(seed: int, uid: int) -> str:
    bucket = stable_rank(seed, "role", uid) % 100
    if bucket < 70:
        return "TRAIN"
    if bucket < 85:
        return "VALIDATION"
    return "FINAL_TEST"


def role_window(role: str) -> tuple[int, int]:
    return {
        "TRAIN": (0, TRAIN_END),
        "VALIDATION": (TRAIN_END + 1, VALIDATION_END),
        "FINAL_TEST": (VALIDATION_END + 1, FINAL_TEST_END),
    }[role]


def n_bucket(value: int) -> str:
    return next(label for lower, upper, label in N_BUCKETS if lower <= value <= upper)


def support_bucket(value: int) -> str:
    if value == 0:
        return "0"
    if value < 10:
        return "1-9"
    if value < 50:
        return "10-49"
    return "50+"


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def push_case(heap: list[tuple[int, int, UserCase]], case: UserCase, limit: int) -> None:
    entry = (-case.rank, -case.uid, case)
    if len(heap) < limit:
        heapq.heappush(heap, entry)
    elif entry > heap[0]:
        heapq.heapreplace(heap, entry)


def select_target(role: str, events: list[Rating], positive_threshold: float) -> Rating | None:
    start, end = role_window(role)
    candidates = [event for event in events if start <= event.timestamp <= end and event.rating >= positive_threshold]
    return max(candidates, key=lambda event: (event.timestamp, event.movie_id), default=None)


def load_cases(ratings_path: Path, seed: int, users_by_role: dict[str, int], positive_threshold: float):
    heaps: dict[tuple[str, str], list[tuple[int, int, UserCase]]] = defaultdict(list)
    train_support: Counter[int] = Counter()

    def finish_user(uid: int | None, events: list[Rating]) -> None:
        if uid is None:
            return
        events.sort(key=lambda event: (event.timestamp, event.movie_id))
        role = role_for_user(seed, uid)
        if role == "TRAIN":
            train_support.update(event.movie_id for event in events if event.timestamp <= TRAIN_END)
        target = select_target(role, events, positive_threshold)
        if target is None:
            return
        history = [event for event in events if event.timestamp < target.timestamp]
        group = "HIGH" if len(history) >= 50 else "LOW"
        case = UserCase(uid, role, target, history, stable_rank(seed, "sample", uid))
        push_case(heaps[(role, group)], case, max(users_by_role[role], 100))

    current_uid: int | None = None
    current_events: list[Rating] = []
    with ratings_path.open("r", encoding="utf-8", newline="") as stream:
        for row in csv.DictReader(stream):
            uid = int(row["userId"])
            if current_uid is not None and uid != current_uid:
                finish_user(current_uid, current_events)
                current_events = []
            current_uid = uid
            current_events.append(Rating(int(row["movieId"]), float(row["rating"]), int(row["timestamp"])))
    finish_user(current_uid, current_events)

    selected: dict[str, list[UserCase]] = {}
    for role in ROLES:
        goal = users_by_role[role]
        high = sorted((entry[2] for entry in heaps[(role, "HIGH")]), key=lambda case: (case.rank, case.uid))
        low = sorted((entry[2] for entry in heaps[(role, "LOW")]), key=lambda case: (case.rank, case.uid))
        high_goal = min(max(100, goal // 2), goal)
        chosen = high[:high_goal]
        chosen.extend(low[: goal - len(chosen)])
        if len(chosen) < goal:
            remaining = goal - len(chosen)
            chosen.extend(high[high_goal: high_goal + remaining])
        selected[role] = sorted(chosen[:goal], key=lambda case: case.uid)
    return selected, train_support


def load_movie_ids(movies_path: Path) -> list[int]:
    with movies_path.open("r", encoding="utf-8", newline="") as stream:
        return [int(row["movieId"]) for row in csv.DictReader(stream)]


def shares(values: list[str]) -> dict[str, float]:
    counts = Counter(values)
    total = sum(counts.values())
    return {key: counts[key] / total for key in sorted(counts)} if total else {}


def psi(left: dict[str, float], right: dict[str, float]) -> float:
    epsilon = 1e-9
    keys = set(left) | set(right)
    return sum((left.get(key, 0.0) - right.get(key, 0.0)) *
               math.log((left.get(key, 0.0) + epsilon) / (right.get(key, 0.0) + epsilon))
               for key in keys)


def js_distance(left: dict[str, float], right: dict[str, float]) -> float:
    keys = set(left) | set(right)
    middle = {key: (left.get(key, 0.0) + right.get(key, 0.0)) / 2 for key in keys}

    def divergence(source: dict[str, float]) -> float:
        return sum(value * math.log2(value / middle[key]) for key, value in source.items() if value > 0)

    return math.sqrt((divergence(left) + divergence(right)) / 2)


def compare_distributions(left: dict[str, float], right: dict[str, float]) -> dict[str, float]:
    keys = set(left) | set(right)
    return {
        "psi": psi(left, right),
        "jensen_shannon_distance_base2": js_distance(left, right),
        "max_percentage_point_difference": max((abs(left.get(k, 0) - right.get(k, 0)) for k in keys), default=0),
    }


def json_digest(rows: list[dict]) -> str:
    payload = "\n".join(json.dumps(row, sort_keys=True, separators=(",", ":")) for row in rows).encode()
    return hashlib.sha256(payload).hexdigest()


def write_jsonl(path: Path, rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")


def build(args: argparse.Namespace) -> dict:
    ratings_path = args.movielens_root / "ratings.csv"
    movies_path = args.movielens_root / "movies.csv"
    args.output.mkdir(parents=True, exist_ok=False)
    users_by_role = {
        "TRAIN": args.train_users if args.train_users is not None else args.users_per_role,
        "VALIDATION": args.validation_users if args.validation_users is not None else args.users_per_role,
        "FINAL_TEST": args.final_test_users if args.final_test_users is not None else args.users_per_role,
    }
    selected, train_support = load_cases(ratings_path, args.seed, users_by_role, args.positive_threshold)
    movie_ids = [movie_id for movie_id in load_movie_ids(movies_path) if train_support[movie_id] > 0]
    movie_id_set = set(movie_ids)

    episodes: list[dict] = []
    candidates: list[dict] = []
    role_profiles: dict[str, dict[str, dict[str, float]]] = {}
    cell_counts: Counter[tuple[str, str]] = Counter()

    for role in ROLES:
        cases = selected[role]
        if len(cases) < users_by_role[role]:
            raise RuntimeError(f"{role}: only {len(cases)} eligible users; requested {users_by_role[role]}")
        role_profiles[role] = {
            "history_bucket": shares([n_bucket(len(case.history)) for case in cases]),
            "target_train_support_bucket": shares([
                support_bucket(max(0, train_support[case.target.movie_id] - (1 if role == "TRAIN" else 0)))
                for case in cases
            ]),
            "target_year": shares([
                str(datetime.fromtimestamp(case.target.timestamp, timezone.utc).year) for case in cases
            ]),
            "target_rating": shares([f"{case.target.rating:.1f}" for case in cases]),
        }
        for case in cases:
            rng = random.Random(stable_rank(args.seed, "candidates", case.uid, case.target.movie_id))
            excluded = {event.movie_id for event in case.history}
            excluded.add(case.target.movie_id)
            sampled: list[int] = []
            sampled_set: set[int] = set()
            sample_goal = min(args.candidate_count - 1, len(movie_ids) - len(excluded & movie_id_set))
            while len(sampled) < sample_goal:
                movie_id = movie_ids[rng.randrange(len(movie_ids))]
                if movie_id in excluded or movie_id in sampled_set:
                    continue
                sampled.append(movie_id)
                sampled_set.add(movie_id)
            candidate_ids = [case.target.movie_id, *sampled]
            candidate_digest = hashlib.sha256(",".join(map(str, candidate_ids)).encode()).hexdigest()
            for rank, movie_id in enumerate(candidate_ids):
                candidates.append({
                    "role": role,
                    "uid": case.uid,
                    "target_movie_id": case.target.movie_id,
                    "candidate_movie_id": movie_id,
                    "candidate_rank": rank,
                    "label_state": "POSITIVE_OBSERVED" if movie_id == case.target.movie_id else "UNKNOWN_SAMPLED",
                })
            for requested_n in N_VALUES:
                if requested_n > len(case.history):
                    continue
                history = case.history[-requested_n:] if requested_n else []
                bucket = n_bucket(requested_n)
                cell_counts[(role, bucket)] += 1
                episodes.append({
                    "role": role,
                    "uid": case.uid,
                    "prediction_at": case.target.timestamp,
                    "target_movie_id": case.target.movie_id,
                    "target_rating": case.target.rating,
                    "total_history_count": len(case.history),
                    "supported_history_count": sum(1 for event in history if train_support[event.movie_id] > 0),
                    "n": requested_n,
                    "n_bucket": bucket,
                    "candidate_digest": candidate_digest,
                    "history": [[event.movie_id, event.rating, event.timestamp] for event in history],
                })

    comparisons = {}
    shifted = False
    for other in ("VALIDATION", "FINAL_TEST"):
        pair = f"TRAIN_vs_{other}"
        comparisons[pair] = {}
        for axis in role_profiles["TRAIN"]:
            result = compare_distributions(role_profiles["TRAIN"][axis], role_profiles[other][axis])
            comparisons[pair][axis] = result
            shifted |= (result["psi"] >= 0.1 or result["jensen_shannon_distance_base2"] >= 0.1 or
                        result["max_percentage_point_difference"] >= 0.05)

    insufficient = [
        {"role": role, "n_bucket": bucket, "users": count}
        for (role, bucket), count in sorted(cell_counts.items()) if count < 100
    ]
    distribution_status = "INSUFFICIENT_SAMPLE" if insufficient else ("DISTRIBUTION_SHIFT" if shifted else "PASS")
    episodes.sort(key=lambda row: (row["role"], row["uid"], row["n"]))
    candidates.sort(key=lambda row: (row["role"], row["uid"], row["candidate_rank"]))
    write_jsonl(args.output / "episodes.jsonl", episodes)
    write_jsonl(args.output / "candidates.jsonl", candidates)

    report = {
        "schema_version": 1,
        "status": distribution_status,
        "thresholds": {"psi": 0.1, "jensen_shannon_distance_base2": 0.1,
                       "max_percentage_point_difference": 0.05, "minimum_users_per_required_cell": 100},
        "role_profiles": role_profiles,
        "comparisons": comparisons,
        "role_n_cell_counts": {f"{role}:{bucket}": count for (role, bucket), count in sorted(cell_counts.items())},
        "insufficient_cells": insufficient,
    }
    (args.output / "split-distribution-report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    manifest = {
        "schema_version": 1,
        "status": "PASS" if not insufficient else "BLOCKED",
        "created_at": datetime.now(timezone.utc).isoformat(),
        "seed": args.seed,
        "cutoffs": {"train_end": TRAIN_END, "validation_end": VALIDATION_END, "final_test_end": FINAL_TEST_END},
        "positive_threshold": args.positive_threshold,
        "input": {"ratings_sha256": file_sha256(ratings_path), "movies_sha256": file_sha256(movies_path)},
        "users": {role: len(selected[role]) for role in ROLES},
        "episodes": {"rows": len(episodes), "sha256": json_digest(episodes)},
        "candidates": {"rows": len(candidates), "sha256": json_digest(candidates)},
        "distribution_report": {"status": distribution_status,
                                "sha256": file_sha256(args.output / "split-distribution-report.json")},
    }
    (args.output / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    return manifest


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--movielens-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--seed", type=int, default=621)
    parser.add_argument("--users-per-role", type=int, default=200)
    parser.add_argument("--train-users", type=int)
    parser.add_argument("--validation-users", type=int)
    parser.add_argument("--final-test-users", type=int)
    parser.add_argument("--candidate-count", type=int, default=100)
    parser.add_argument("--positive-threshold", type=float, default=4.0)
    return parser.parse_args()


if __name__ == "__main__":
    print(json.dumps(build(parse_args()), ensure_ascii=False, indent=2))
