"""Private fixture runner; prints counts/hashes/errors only, never ratings or IDs.

Fixture JSON: {cases: [{name, request, expected_npz, features_npz?}]}. Paths are
relative to that file. Request uses ratings[{movieId,score}], dismissedMovieIds,
watchedMovieIds (explicit caller exclusion), candidateIds (optional).
Expected NPZ: movie_id,score; optional feature fixture: movie_id,matrix.
This tool never trains, filters, samples histories or creates expected results.
"""

import argparse
import importlib.util
import json
import sys
import time
from pathlib import Path

import numpy as np

from feelm_consumer.gbt.artifacts import canonical, load_bundle, runtime, sha
from feelm_consumer.gbt.inputs import merge_inputs
from feelm_consumer.gbt.scorer import Scorer


def parity(ids, scores, expected_ids, expected):
    for axis in (ids, expected_ids):
        if axis.ndim != 1 or axis.dtype.kind not in "iu" or len(np.unique(axis)) != len(axis):
            raise AssertionError("INVALID_PARITY_ID_AXIS")
    if not np.array_equal(ids, expected_ids):
        raise AssertionError("PARITY_ID_MISMATCH")
    for values in (scores, expected):
        if values.shape != ids.shape or values.dtype.kind != "f" or not np.isfinite(values).all():
            raise AssertionError("INVALID_PARITY_SCORES")
    error = float(np.max(np.abs(scores - expected))) if len(scores) else 0.0
    if error > 1e-6:
        raise AssertionError("Full score parity failed")
    order, reforder = np.lexsort((ids, -scores)), np.lexsort((expected_ids, -expected))
    for k in (100, 500):
        if not np.array_equal(ids[order[:k]], expected_ids[reforder[:k]]):
            raise AssertionError("PARITY_RANK_MISMATCH")
    return {"count": len(ids), "max_abs_error": error, "bit_exact": bool(np.array_equal(scores, expected)),
            "top100_identical": True, "top500_identical": True}


def run(bundle, revision, fixtures, threads=2):
    started = time.monotonic()
    scorer = Scorer(load_bundle(bundle, expected_revision=revision, threads=threads))
    result = {"runtime": runtime(), "revision": revision, "load_seconds": time.monotonic() - started, "cases": []}
    root = Path(fixtures).parent
    cases = json.loads(Path(fixtures).read_bytes())["cases"]
    for case in cases:
        request = json.loads((root / case["request"]).read_bytes())
        ratings = [(r["movieId"], r["score"]) for r in request["ratings"]]
        options = {"dismissed_movie_ids": request.get("dismissedMovieIds", []),
                   "excluded_movie_ids": request.get("watchedMovieIds", []),
                   "candidate_ids": request.get("candidateIds")}
        start = time.monotonic()
        output = scorer.score(ratings, **options)
        elapsed = time.monotonic() - start
        with np.load(root / case["expected_npz"], allow_pickle=False) as z:
            check = parity(output.movie_ids, output.scores, z["movie_id"], z["score"])
        row = {"case": case["name"], "input_count": output.input_count, "seconds": elapsed, **check,
               "input_sha256": sha(canonical(request)),
               "expected_sha256": sha((root / case["expected_npz"]).read_bytes())}
        if "features_npz" in case:
            h = merge_inputs(ratings, options["dismissed_movie_ids"], options["excluded_movie_ids"])
            with np.load(root / case["features_npz"], allow_pickle=False) as z:
                positions = np.array([scorer.features.positions[int(mid)] for mid in z["movie_id"]])
                matrix = scorer.features.prepare(h.ratings).matrix(positions)
                expected = z["matrix"]
                if (expected.shape != matrix.shape or expected.dtype.kind != "f"
                        or not np.isfinite(expected).all() or not np.isfinite(matrix).all()):
                    raise AssertionError("INVALID_PARITY_FEATURES")
                error = float(np.max(np.abs(matrix - expected)))
                if error > 1e-6:
                    raise AssertionError("Feature parity failed")
                row["feature_max_abs_error"] = error
        result["cases"].append(row)
        print(json.dumps(row), flush=True)
    if sys.platform.startswith("linux"):
        import resource

        result["resource"] = {"rss_peak_mib": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024}
        for name in ("memory.current", "memory.peak", "memory.max", "memory.events", "cpu.max",
                     "memory/memory.max_usage_in_bytes", "memory/memory.limit_in_bytes", "memory/memory.failcnt",
                     "memory/memory.oom_control", "cpu/cpu.cfs_quota_us", "cpu/cpu.cfs_period_us"):
            path = Path("/sys/fs/cgroup") / name
            if path.exists():
                result["resource"][name] = path.read_text().strip()
    result["dependency_isolation"] = {n: importlib.util.find_spec(n) is None for n in ("pandas", "pyspark")}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("bundle", "expected-revision", "fixtures", "report"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--threads", type=int, default=2)
    args = parser.parse_args()
    if Path(args.report).exists():
        raise FileExistsError("Never overwrite a verification report")
    report = run(args.bundle, args.expected_revision, args.fixtures, args.threads)
    with open(args.report, "xb") as stream:
        stream.write(canonical(report))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        # Even unexpected malformed private fixtures must not dump IDs/paths in a traceback.
        print(json.dumps({"status": "FAILED", "error_type": type(exc).__name__}), file=sys.stderr)
        raise SystemExit(1) from None
