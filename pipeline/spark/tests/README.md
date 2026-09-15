# Spark test groups

The MR suite keeps every `test_*.py` module in exactly one CI group. Run the
partition check before the groups:

```bash
uv run python -m ci_test_groups verify
uv run python -m ci_test_groups unit -vv --durations=20
uv run python -m ci_test_groups spark-als-a-d -vv --durations=20
uv run python -m ci_test_groups spark-als-e-z -vv --durations=20
uv run python -m ci_test_groups spark-other -vv --durations=20
```

## Classification

`unit` contains tests audited not to create a real `SparkSession` or
`SparkContext`. Imports, settings, pure Python policies, mocked job boundaries,
and local JSON or small artifact lifecycle checks belong here. This group can
fail quickly before any JVM startup.

The three `spark-*` groups contain tests that create a local Spark session or
exercise Spark DataFrames, ML, Parquet/JSON, Hadoop filesystem APIs, or a real
Spark job boundary. They run in separate Python processes so a JVM and its
native workers exit before the next group. They are not run concurrently because
they share driver ports, memory limits, and local temporary storage on one
isolated Runner.

Every group prints a `CI_TEST_GROUP_METRICS` line with wall time and, on Linux,
process CPU time, maximum RSS, and block I/O counters. Pytest's slowest-test
durations remain in the same log.

The audited unit file list is the `UNIT_TEST_FILES` constant in
`ci_test_groups.py`. Every other current or newly added test defaults to
`spark-other`; moving a file into `unit` requires confirming that it starts no
real Spark context. The partition verifier rejects missing declared files and
guarantees that the four groups contain every discovered test file once.

Current unit files:

- `tests/batch/test_als_baseline_job.py`
- `tests/batch/test_als_input_manifest_job.py`
- `tests/batch/test_als_selection.py`
- `tests/batch/test_als_serving.py`
- `tests/batch/test_als_settings.py`
- `tests/batch/test_content_artifacts.py`
- `tests/batch/test_content_catalog.py`
- `tests/batch/test_content_features.py`
- `tests/batch/test_content_hybrid_evaluation.py`
- `tests/batch/test_content_hybrid_scoring.py`
- `tests/batch/test_research_handoff_verification.py`
- `tests/batch/test_research_reference.py`
- `tests/common/test_ci_test_groups.py`
- `tests/common/test_settings.py`
- `tests/common/test_spark.py`

Current Spark integration files are the remaining discovered files:

- `tests/batch/movie_id_mapping/test_pipeline.py`
- `tests/batch/test_als_artifacts.py`
- `tests/batch/test_als_baseline_orchestration.py`
- `tests/batch/test_als_candidates.py`
- `tests/batch/test_als_chunked_evaluation.py`
- `tests/batch/test_als_chunked_evidence.py`
- `tests/batch/test_als_evaluation.py`
- `tests/batch/test_als_input_snapshot.py`
- `tests/batch/test_als_inputs.py`
- `tests/batch/test_als_integration_fixture.py`
- `tests/batch/test_als_metrics.py`
- `tests/batch/test_als_run_report.py`
- `tests/batch/test_als_splits.py`
- `tests/batch/test_als_training.py`
- `tests/batch/test_artifact_transfer.py`
- `tests/batch/test_content_hybrid_local_e2e.py`
- `tests/batch/test_content_serving_artifacts.py`
- `tests/batch/test_content_serving_local_e2e.py`
- `tests/batch/test_movie_metadata.py`
- `tests/batch/test_parquet_roundtrip.py`
- `tests/batch/test_tmdb_snapshot.py`
