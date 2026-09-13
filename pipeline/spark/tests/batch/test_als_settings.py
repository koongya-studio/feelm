import argparse
from pathlib import Path

import pytest

from batch.train.settings import DEFAULT_ALS_CONFIGS, AlsConfig, AlsJobSettings


def test_uses_reproducible_baseline_defaults() -> None:
    settings = AlsJobSettings.from_sources(environ={}, argv=[])

    assert settings.seed == 42
    assert settings.train_ratio == 0.8
    assert settings.validation_ratio == 0.1
    assert settings.test_ratio == pytest.approx(0.1)
    assert settings.positive_rating_threshold == 4.0
    assert settings.candidate_size == 500
    assert settings.user_chunk_size > 0
    assert settings.als_configs == DEFAULT_ALS_CONFIGS


def test_cli_overrides_paths_and_training_settings() -> None:
    settings = AlsJobSettings.from_sources(
        environ={"RECOMMENDATION_ALS_SEED": "7"},
        argv=[
            "--input-uri",
            "hdfs://cluster/processed/movie-mapping/v1",
            "--output-uri",
            "hdfs://cluster/models/als/test",
            "--seed",
            "11",
            "--candidate-size",
            "100",
            "--user-chunk-size",
            "2500",
            "--als-config",
            "16:0.2:5",
        ],
    )

    ratings_uri, service_map_uri = settings.require_input_uris()
    assert ratings_uri.endswith("/ratings")
    assert service_map_uri.endswith("/service-movie-id-map")
    assert settings.output_uri == "hdfs://cluster/models/als/test"
    assert settings.seed == 11
    assert settings.candidate_size == 100
    assert settings.user_chunk_size == 2500
    assert settings.als_configs == (AlsConfig(16, 0.2, 5),)


def test_cli_value_is_used_before_invalid_environment_value() -> None:
    settings = AlsJobSettings.from_sources(
        environ={"RECOMMENDATION_ALS_SEED": "not-an-int"},
        argv=["--seed", "11"],
    )

    assert settings.seed == 11


def test_rejects_invalid_split_ratios() -> None:
    with pytest.raises(ValueError, match="positive test ratio"):
        AlsJobSettings.from_sources(
            environ={},
            argv=["--train-ratio", "0.9", "--validation-ratio", "0.1"],
        )


def test_rejects_zero_test_ratio_without_float_rounding_error() -> None:
    with pytest.raises(ValueError, match="positive test ratio"):
        AlsJobSettings.from_sources(
            environ={},
            argv=["--train-ratio", "0.7", "--validation-ratio", "0.3"],
        )


def test_accepts_missing_optional_output_uri() -> None:
    settings = AlsJobSettings.from_sources(environ={}, argv=["--input-uri", "/movie-mapping/v1"])

    ratings_uri, service_map_uri = settings.require_input_uris()

    assert ratings_uri == "/movie-mapping/v1/ratings"
    assert service_map_uri == "/movie-mapping/v1/service-movie-id-map"
    assert settings.output_uri is None


def test_requires_input_uri() -> None:
    settings = AlsJobSettings.from_sources(environ={}, argv=["--output-uri", "/models/als/run"])

    with pytest.raises(ValueError, match="input URI is required"):
        settings.require_input_uris()


@pytest.mark.parametrize(
    ("argv", "message"),
    [
        (["--candidate-size", "9"], "at least 10"),
        (["--user-chunk-size", "0"], "user chunk size"),
        (["--user-chunk-size", "-1"], "user chunk size"),
        (["--positive-rating-threshold", "5.5"], "between 0.5 and 5.0"),
    ],
)
def test_rejects_invalid_evaluation_settings(
    argv: list[str],
    message: str,
) -> None:
    with pytest.raises(ValueError, match=message):
        AlsJobSettings.from_sources(environ={}, argv=argv)


def test_rejects_invalid_als_config() -> None:
    with pytest.raises(argparse.ArgumentTypeError, match="rank > 0"):
        AlsJobSettings.from_sources(
            environ={"RECOMMENDATION_ALS_CONFIGS": "0:0.1:10"},
            argv=[],
        )


@pytest.mark.parametrize("reg_param", ["nan", "inf", "-inf"])
def test_rejects_non_finite_als_reg_param(reg_param: str) -> None:
    with pytest.raises(SystemExit):
        AlsJobSettings.from_sources(
            environ={},
            argv=["--als-config", f"64:{reg_param}:10"],
        )


def test_validate_is_default_and_preserves_existing_requirements() -> None:
    settings = AlsJobSettings.from_sources(environ={}, argv=["--input-uri", "/movie-mapping/v1"])

    assert settings.mode == "validate"
    assert settings.require_input_uris() == (
        "/movie-mapping/v1/ratings",
        "/movie-mapping/v1/service-movie-id-map",
    )


def test_run_mode_requires_write_and_reproduction_values() -> None:
    for missing_option in (
        "--output-uri",
        "--input-manifest-uri",
        "--input-snapshot",
        "--implementation-revision",
    ):
        arguments = [
            "--mode",
            "run",
            "--input-uri",
            "file:///movie-mapping/v1",
            "--output-uri",
            "file:///models",
            "--input-manifest-uri",
            "file:///snapshots/fixture-v1/manifest.json",
            "--input-snapshot",
            "sha256:fixture-v1",
            "--implementation-revision",
            "abc123",
        ]
        del arguments[arguments.index(missing_option) : arguments.index(missing_option) + 2]
        message = missing_option.removeprefix("--").replace("-", " ")
        with pytest.raises(ValueError, match=f"(?i){message}"):
            AlsJobSettings.from_sources(environ={}, argv=arguments)


def test_run_context_is_fully_resolved_before_spark_creation() -> None:
    settings = AlsJobSettings.from_sources(
        environ={},
        argv=[
            "--mode",
            "run",
            "--input-uri",
            "file:///movie-mapping/v1",
            "--output-uri",
            "file:///models",
            "--input-manifest-uri",
            "file:///snapshots/fixture-v1/manifest.json",
            "--input-snapshot",
            "sha256:fixture-v1",
            "--run-id",
            "run-20260909-a1",
            "--implementation-revision",
            "abc123",
        ],
    )
    context = settings.require_run_context()
    assert context.output_uri == "file:///models"
    assert context.input_manifest_uri == "file:///snapshots/fixture-v1/manifest.json"
    assert context.input_snapshot == "sha256:fixture-v1"
    assert context.run_id == "run-20260909-a1"
    assert context.implementation_revision == "abc123"


def test_new_run_fields_are_cli_only_and_run_id_is_generated() -> None:
    common = [
        "--mode",
        "run",
        "--input-uri",
        "file:///input",
        "--output-uri",
        "file:///output",
        "--input-manifest-uri",
        "file:///snapshots/fixture-v1/manifest.json",
        "--input-snapshot",
        "sha256:fixture-v1",
        "--implementation-revision",
        "cli-revision",
    ]
    environment = {"RECOMMENDATION_IMPLEMENTATION_REVISION": "environment-revision"}
    without_cli_revision = common[:-2]
    with pytest.raises(ValueError, match="(?i)implementation revision"):
        AlsJobSettings.from_sources(environ=environment, argv=without_cli_revision)

    first = AlsJobSettings.from_sources(environ=environment, argv=common).require_run_context()
    second = AlsJobSettings.from_sources(environ={}, argv=common).require_run_context()
    assert first.implementation_revision == "cli-revision"
    assert first.run_id != second.run_id
    assert first.run_id.startswith("als-")


@pytest.mark.parametrize(
    "arguments",
    [
        ["--input-uri", "/same", "--output-uri", "/same"],
        ["--input-uri", "/same/input", "--output-uri", "/same/input/models"],
        ["--input-uri", "hdfs://user:password@cluster/input"],
        ["--output-uri", "s3://bucket/models"],
        ["--run-id", "../escape"],
        ["--input-snapshot", "latest"],
    ],
)
def test_run_rejects_unsafe_paths_and_mutable_identifiers(arguments) -> None:
    base = [
        "--mode",
        "run",
        "--input-uri",
        "file:///input",
        "--output-uri",
        "file:///output",
        "--input-manifest-uri",
        "file:///snapshots/fixture-v1/manifest.json",
        "--input-snapshot",
        "sha256:fixture-v1",
        "--implementation-revision",
        "abc123",
    ]
    overridden = {arguments[index]: arguments[index + 1] for index in range(0, len(arguments), 2)}
    options = {base[index]: base[index + 1] for index in range(0, len(base), 2)}
    options.update(overridden)
    flattened = [value for pair in options.items() for value in pair]
    with pytest.raises(ValueError):
        AlsJobSettings.from_sources(environ={}, argv=flattened)


@pytest.mark.parametrize("seed", [-(2**63) - 1, 2**63])
def test_rejects_seed_outside_split_and_als_long_contract(seed: int) -> None:
    with pytest.raises(ValueError, match="seed"):
        AlsJobSettings.from_sources(environ={}, argv=["--seed", str(seed)])


@pytest.mark.parametrize("seed", [-(2**63), 2**63 - 1])
def test_accepts_signed_long_seed_boundaries(seed: int) -> None:
    settings = AlsJobSettings.from_sources(environ={}, argv=["--seed", str(seed)])
    assert settings.seed == seed


@pytest.mark.parametrize("source", ["cli", "environment"])
def test_rejects_duplicate_candidate_configs_before_training(source: str) -> None:
    environment = {}
    argv = []
    # Numerically equal tuples are duplicates even with different float spelling.
    if source == "cli":
        argv = ["--als-config", "32:0.1:10", "--als-config", "32:0.10:10"]
    else:
        environment["RECOMMENDATION_ALS_CONFIGS"] = "32:0.1:10,32:0.10:10"
    with pytest.raises(ValueError, match="duplicate"):
        AlsJobSettings.from_sources(environ=environment, argv=argv)


def run_arguments(*overrides: str) -> list[str]:
    options = {
        "--mode": "run",
        "--input-uri": "file:///input",
        "--output-uri": "file:///output",
        "--input-manifest-uri": "file:///snapshots/fixture-v1/manifest.json",
        "--input-snapshot": "sha256:fixture-v1",
        "--implementation-revision": "abc123",
    }
    options.update(
        {overrides[index]: overrides[index + 1] for index in range(0, len(overrides), 2)}
    )
    return [value for pair in options.items() for value in pair]


@pytest.mark.parametrize(
    ("input_uri", "output_uri"),
    [
        ("file:///", "file:///models"),
        ("file:///models", "file:///"),
        ("file:///data/input", "file:///data/input"),
        ("file:///data/input", "file:///data/input/models"),
        ("file:///data/input/ratings", "file:///data/input"),
        ("file:/data/input", "file:///data/input/models"),
        ("file://localhost/data/input/models", "file:///data/input"),
        ("hdfs://namenode/input", "hdfs://namenode:8020/input/models"),
        ("hdfs://NAMENODE:8020/input/models", "hdfs://namenode/input"),
    ],
)
def test_run_rejects_same_filesystem_equal_parent_or_child_uri_spellings_before_spark(
    input_uri, output_uri
):
    with pytest.raises(ValueError, match="(?i)explicit|overlap|path"):
        AlsJobSettings.from_sources(
            environ={},
            argv=run_arguments("--input-uri", input_uri, "--output-uri", output_uri),
        )


def test_run_rejects_relative_and_absolute_paths_for_the_same_location_before_spark(
    tmp_path, monkeypatch
):
    monkeypatch.chdir(tmp_path)
    absolute_output = (Path.cwd() / "input" / "models").as_posix()

    with pytest.raises(ValueError, match="(?i)explicit|overlap|path"):
        AlsJobSettings.from_sources(
            environ={},
            argv=run_arguments(
                "--input-uri",
                "input",
                "--output-uri",
                absolute_output,
            ),
        )


@pytest.mark.parametrize(
    ("option", "ambiguous_uri"),
    [
        ("--input-uri", "/input"),
        ("--input-uri", "hdfs:/input"),
        ("--input-uri", "hdfs:///input"),
        ("--output-uri", "/output"),
        ("--input-manifest-uri", "manifest.json"),
        ("--input-manifest-uri", "file:manifest.json"),
    ],
)
def test_run_rejects_locations_that_depend_on_default_filesystem_or_working_directory(
    option, ambiguous_uri
):
    with pytest.raises(ValueError, match="(?i)explicit|absolute|authority|scheme"):
        AlsJobSettings.from_sources(
            environ={},
            argv=run_arguments(option, ambiguous_uri),
        )


@pytest.mark.parametrize("option", ["--input-uri", "--input-manifest-uri"])
@pytest.mark.parametrize("ambiguous_uri", ["/input", "hdfs:/input", "hdfs:///input"])
def test_run_cannot_mix_default_filesystem_locations_with_explicit_hdfs_output(
    option, ambiguous_uri
):
    with pytest.raises(ValueError, match="(?i)explicit|authority|scheme"):
        AlsJobSettings.from_sources(
            environ={},
            argv=run_arguments(
                "--input-uri",
                "hdfs://namenode:8020/input",
                "--output-uri",
                "hdfs://namenode:8020/output",
                "--input-manifest-uri",
                "hdfs://namenode:8020/snapshot/manifest.json",
                option,
                ambiguous_uri,
            ),
        )
