"""Run the complete Spark test suite in CI-safe, explicit groups."""

from __future__ import annotations

import argparse
import re
import time
from collections.abc import Sequence
from pathlib import Path

import pytest

PROJECT_ROOT = Path(__file__).resolve().parent
TEST_ROOT = PROJECT_ROOT / "tests"

# These files were audited not to create a real SparkSession or SparkContext.
# New tests intentionally default to a serial Spark group until they are audited.
UNIT_TEST_FILES = frozenset(
    {
        "tests/batch/test_als_baseline_job.py",
        "tests/batch/test_als_input_manifest_job.py",
        "tests/batch/test_als_selection.py",
        "tests/batch/test_als_serving.py",
        "tests/batch/test_als_settings.py",
        "tests/batch/test_content_artifacts.py",
        "tests/batch/test_content_catalog.py",
        "tests/batch/test_content_features.py",
        "tests/batch/test_content_hybrid_evaluation.py",
        "tests/batch/test_content_hybrid_scoring.py",
        "tests/batch/test_research_handoff_verification.py",
        "tests/batch/test_research_reference.py",
        "tests/common/test_ci_test_groups.py",
        "tests/common/test_settings.py",
        "tests/common/test_spark.py",
        "tests/streaming/test_party_artifacts.py",
        "tests/streaming/test_party_factors.py",
        "tests/streaming/test_party_kafka_stream.py",
        "tests/streaming/test_party_processor.py",
        "tests/streaming/test_party_redis_store.py",
        "tests/streaming/test_party_runtime.py",
        "tests/streaming/test_party_scoring.py",
    }
)

RUN_GROUPS = ("unit", "spark-als-a-d", "spark-als-e-z", "spark-other")


def _usage() -> tuple[float, float, int, int, int] | None:
    """Return process-tree usage for Linux CI when the platform provides it."""
    try:
        import resource
    except ImportError:
        return None

    self_usage = resource.getrusage(resource.RUSAGE_SELF)
    child_usage = resource.getrusage(resource.RUSAGE_CHILDREN)
    return (
        self_usage.ru_utime + child_usage.ru_utime,
        self_usage.ru_stime + child_usage.ru_stime,
        max(self_usage.ru_maxrss, child_usage.ru_maxrss),
        self_usage.ru_inblock + child_usage.ru_inblock,
        self_usage.ru_oublock + child_usage.ru_oublock,
    )


def run_group(group: str, pytest_arguments: Sequence[str], selected: Sequence[str]) -> int:
    """Run one group and emit stable wall/CPU/memory/block-I/O metrics."""
    print(f"CI_TEST_GROUP_STARTED group={group} files={len(selected)}", flush=True)
    started_at = time.perf_counter()
    before = _usage()
    code = int(pytest.main([*pytest_arguments, *selected]))
    elapsed = time.perf_counter() - started_at
    after = _usage()

    metrics = [f"group={group}", f"wall_seconds={elapsed:.3f}"]
    if before is not None and after is not None:
        metrics.extend(
            (
                f"user_seconds={after[0] - before[0]:.3f}",
                f"system_seconds={after[1] - before[1]:.3f}",
                f"max_rss_kb={after[2]}",
                f"input_blocks={after[3] - before[3]}",
                f"output_blocks={after[4] - before[4]}",
            )
        )
    print("CI_TEST_GROUP_METRICS " + " ".join(metrics), flush=True)
    return code


def discover_test_files(project_root: Path = PROJECT_ROOT) -> set[str]:
    """Return every collected test module relative to the Spark project root."""
    return {
        path.relative_to(project_root).as_posix()
        for path in (project_root / "tests").rglob("test_*.py")
        if path.is_file()
    }


def partition_test_files(
    project_root: Path = PROJECT_ROOT,
    unit_test_files: frozenset[str] = UNIT_TEST_FILES,
) -> dict[str, tuple[str, ...]]:
    """Partition all test files exactly once, defaulting unknown files to Spark."""
    discovered = discover_test_files(project_root)
    missing = unit_test_files - discovered
    if missing:
        raise ValueError(f"unit test files are missing: {', '.join(sorted(missing))}")

    groups: dict[str, list[str]] = {name: [] for name in RUN_GROUPS}
    for path in sorted(discovered):
        if path in unit_test_files:
            groups["unit"].append(path)
        elif path == "tests/batch/movie_id_mapping/test_pipeline.py" or re.fullmatch(
            r"tests/batch/test_als_[a-d][^/]*\.py", path
        ):
            groups["spark-als-a-d"].append(path)
        elif re.fullmatch(r"tests/batch/test_als_[e-z][^/]*\.py", path):
            groups["spark-als-e-z"].append(path)
        else:
            groups["spark-other"].append(path)

    flattened = [path for name in RUN_GROUPS for path in groups[name]]
    if len(flattened) != len(set(flattened)) or set(flattened) != discovered:
        raise ValueError("CI test groups must partition every test file exactly once")
    return {name: tuple(paths) for name, paths in groups.items()}


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("group", choices=("verify", *RUN_GROUPS))
    arguments, pytest_arguments = parser.parse_known_args(argv)
    groups = partition_test_files()

    if arguments.group == "verify":
        counts = " ".join(f"{name}={len(paths)}" for name, paths in groups.items())
        print(f"CI_TEST_GROUPS_VERIFIED total={sum(map(len, groups.values()))} {counts}")
        return 0

    selected = [str(PROJECT_ROOT / path) for path in groups[arguments.group]]
    return run_group(arguments.group, pytest_arguments, selected)


if __name__ == "__main__":
    raise SystemExit(main())
