# ruff: noqa: E501

import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq
import pytest

from feelm_consumer.artifacts import ArtifactError
from feelm_consumer.content import load_content

MAPPING_REVISION = "sha256:" + "a" * 64
METADATA_REVISION = "sha256:" + "b" * 64
CONTENT_REVISION = "sha256:" + "c" * 64
OTHER_REVISION = "sha256:" + "9" * 64
INPUT_SNAPSHOT = "snapshot-a"
SERVICE_IDS = {10, 20, 30}
PRIOR_PROBABILITY = [0.02, 0.03, 0.05, 0.05, 0.10, 0.20, 0.20, 0.15, 0.12, 0.08]
PRIOR_MIDRANK = (np.cumsum(PRIOR_PROBABILITY) - 0.5 * np.asarray(PRIOR_PROBABILITY)).tolist()

FEATURES_SCHEMA = pa.schema([
    pa.field("service_movie_id", pa.int64()),
    pa.field("tmdb_id", pa.int32()),
    # Spark names the list element and marks it non-nullable.
    pa.field("indices", pa.list_(pa.field("element", pa.int32(), nullable=False))),
    pa.field("values", pa.list_(pa.field("element", pa.float64(), nullable=False))),
])
FEATURE_ROWS = [
    {"service_movie_id": 10, "tmdb_id": 100, "indices": [0, 2], "values": [0.6, 0.8]},
    {"service_movie_id": 20, "tmdb_id": 200, "indices": [1, 3], "values": [0.6, 0.8]},
    # A service movie without a content vector is normal, not an error.
    {"service_movie_id": 30, "tmdb_id": 300, "indices": [], "values": []},
]


def _metadata_row(**overrides: object) -> dict[str, object]:
    row = {
        "contract_version": "content-artifacts-v2",
        "content_version": "content-fixture-v1",
        "input_snapshot": INPUT_SNAPSHOT,
        "input_uri": "file:///snapshots/movie-mapping/fixture-v1",
        "mapping_revision": MAPPING_REVISION,
        "movie_metadata_revision": METADATA_REVISION,
        "tmdb_snapshot_revision": "sha256:" + "d" * 64,
        "feature_rule": "structured-4group-v1",
        "eligibility_rule": "language-year-genre-v1",
        "built_at_utc": "2026-09-15T00:00:00Z",
        "spark_version": "4.1.3",
        "service_movie_count": 3,
        "supported_movie_count": 2,
        "vocabulary_size": 6,
        "prior_probability": PRIOR_PROBABILITY,
        "prior_midrank": PRIOR_MIDRANK,
        "prior_user_count": 3,
        "prior_rating_count": 9,
        "content_revision": CONTENT_REVISION,
        "implementation_revision": None,
    }
    row.update(overrides)
    return row


def _write(directory, rows: list[dict[str, object]], schema: pa.Schema | None = None) -> None:
    directory.mkdir(parents=True)
    pq.write_table(pa.Table.from_pylist(rows, schema=schema), directory / "part-00000.parquet")


def _bundle(
    tmp_path,
    *,
    features=None,
    features_schema: pa.Schema = FEATURES_SCHEMA,
    metadata=None,
    vocabulary_rows: int = 6,
    skip: str | None = None,
) -> str:
    # The contract publishes at <output>/content/<content-version>.
    root = tmp_path / "content" / "content-fixture-v1"
    root.mkdir(parents=True)
    if skip != "features":
        _write(root / "features", features if features is not None else FEATURE_ROWS, features_schema)
    if skip != "vocabulary":
        _write(root / "vocabulary", [{"feature_index": index, "token": f"genre:{index}"} for index in range(vocabulary_rows)])
    if skip != "metadata":
        _write(root / "metadata", [metadata if metadata is not None else _metadata_row()])
    return root.as_uri()


def _load(uri: str):
    return load_content(
        uri,
        mapping_revision=MAPPING_REVISION,
        movie_metadata_revision=METADATA_REVISION,
        input_snapshot=INPUT_SNAPSHOT,
        service_movie_ids=SERVICE_IDS,
    )


def test_loads_content_bundle_written_by_spark(tmp_path) -> None:
    content = _load(_bundle(tmp_path))

    assert content.content_version == "content-fixture-v1"
    assert content.vocabulary_size == 6
    assert content.movie_count == 3
    assert content.supported.tolist() == [True, True, False]
    assert content.service_movie_ids.tolist() == [10, 20, 30]
    assert content.row_index(20) == 1
    assert content.row_index(99) is None
    np.testing.assert_allclose(content.prior_midrank, PRIOR_MIDRANK)
    # A profile that only rewards index 0 scores the first movie by its weight.
    profile = np.zeros(6)
    profile[0] = 1.0
    np.testing.assert_allclose(content.scores(profile), [0.6, 0.0, 0.0])


@pytest.mark.parametrize(
    ("overrides", "error"),
    [
        ({"mapping_revision": OTHER_REVISION}, "another artifact generation"),
        ({"movie_metadata_revision": OTHER_REVISION}, "another artifact generation"),
        ({"input_snapshot": "snapshot-b"}, "another artifact generation"),
    ],
)
def test_rejects_content_bundle_from_another_generation(tmp_path, overrides, error) -> None:
    with pytest.raises(ArtifactError, match=error):
        _load(_bundle(tmp_path, metadata=_metadata_row(**overrides)))


@pytest.mark.parametrize(
    ("overrides", "error"),
    [
        ({"contract_version": "content-artifacts-v1"}, "unsupported content artifact"),
        ({"feature_rule": "other-rule"}, "unsupported content feature rule"),
        ({"eligibility_rule": "other-rule"}, "unsupported content feature rule"),
        ({"content_version": ""}, "invalid content version"),
        ({"content_version": "content-other-v1"}, "does not match its path"),
        ({"content_revision": "not-a-digest"}, "invalid content revision"),
        ({"vocabulary_size": 0}, "invalid content vocabulary size"),
        ({"prior_midrank": [0.5] * 9}, "ten bins"),
        ({"prior_midrank": [0.9] + [0.1] * 9}, "finite and ordered"),
        ({"prior_midrank": [0.0] * 10}, "increasing range"),
        ({"prior_midrank": [1.0] * 10}, "increasing range"),
        ({"prior_midrank": [float(index - 5) for index in range(10)]}, "increasing range"),
        ({"prior_probability": [0.2] * 10}, "must be a distribution"),
        ({"prior_probability": [-0.1] + [0.1] * 9 + []}, "must be a distribution"),
        ({"prior_probability": [0.1] * 9}, "ten bins"),
        # The v1 placeholder is internally consistent, so only its flatness gives it away.
        (
            {"prior_probability": [0.1] * 10, "prior_midrank": (np.cumsum([0.1] * 10) - 0.05).tolist()},
            "placeholder distribution",
        ),
        ({"prior_user_count": 0}, "prior counts are missing or invalid"),
        ({"prior_user_count": None}, "prior counts are missing or invalid"),
        ({"prior_rating_count": 2}, "rating count is below its user count"),
        ({"supported_movie_count": 3}, "counts do not match metadata"),
        ({"service_movie_count": 4}, "counts do not match metadata"),
        ({"supported_movie_count": None}, "counts are missing or invalid"),
    ],
)
def test_rejects_invalid_content_metadata(tmp_path, overrides, error) -> None:
    with pytest.raises(ArtifactError, match=error):
        _load(_bundle(tmp_path, metadata=_metadata_row(**overrides)))


@pytest.mark.parametrize("missing", ["features", "vocabulary", "metadata"])
def test_rejects_incomplete_content_bundle(tmp_path, missing) -> None:
    with pytest.raises(ArtifactError):
        _load(_bundle(tmp_path, skip=missing))


def test_rejects_two_metadata_rows(tmp_path) -> None:
    # The contract publishes at <output>/content/<content-version>.
    root = tmp_path / "content" / "content-fixture-v1"
    root.mkdir(parents=True)
    _write(root / "features", FEATURE_ROWS, FEATURES_SCHEMA)
    _write(root / "vocabulary", [{"feature_index": index, "token": f"genre:{index}"} for index in range(6)])
    _write(root / "metadata", [_metadata_row(), _metadata_row()])

    with pytest.raises(ArtifactError, match="exactly one row"):
        _load(root.as_uri())


def test_prior_midrank_must_match_its_probability(tmp_path) -> None:
    """A real probability paired with the v1 placeholder midrank must not load."""
    placeholder = [round(0.1 * (index + 1) - 0.05, 4) for index in range(10)]
    probability = [0.02, 0.03, 0.05, 0.05, 0.10, 0.20, 0.20, 0.15, 0.12, 0.08]

    with pytest.raises(ArtifactError, match="midrank does not match"):
        _load(
            _bundle(
                tmp_path,
                metadata=_metadata_row(prior_probability=probability, prior_midrank=placeholder),
            )
        )


def test_rejects_the_writers_mutable_staging_sibling(tmp_path) -> None:
    """The runtime Consumer must only read the atomically published final path."""
    root = tmp_path / "content" / ".content-fixture-v1.tmp-0123abcd"
    root.mkdir(parents=True)
    _write(root / "features", FEATURE_ROWS, FEATURES_SCHEMA)
    _write(root / "vocabulary", [{"feature_index": index, "token": f"genre:{index}"} for index in range(6)])
    _write(root / "metadata", [_metadata_row()])

    with pytest.raises(ArtifactError, match="does not match its path"):
        _load(root.as_uri())


def test_trailing_slash_does_not_break_the_path_check(tmp_path) -> None:
    assert _load(_bundle(tmp_path) + "/").content_version == "content-fixture-v1"


@pytest.mark.parametrize("directory", [".content-fixture-v1.tmp", ".content-fixture-v1.staging", "content-fixture-v1x"])
def test_rejects_directories_that_are_not_the_published_version(tmp_path, directory) -> None:
    """Only the exact final `<content-version>` directory is readable."""
    root = tmp_path / "content" / directory
    root.mkdir(parents=True)
    _write(root / "features", FEATURE_ROWS, FEATURES_SCHEMA)
    _write(root / "vocabulary", [{"feature_index": index, "token": f"genre:{index}"} for index in range(6)])
    _write(root / "metadata", [_metadata_row()])

    with pytest.raises(ArtifactError, match="does not match its path"):
        _load(root.as_uri())


def test_rejects_vocabulary_size_mismatch(tmp_path) -> None:
    with pytest.raises(ArtifactError, match="vocabulary size does not match"):
        _load(_bundle(tmp_path, vocabulary_rows=5))


@pytest.mark.parametrize(
    ("features", "error"),
    [
        (FEATURE_ROWS[:2], "do not cover exactly the service mapping"),
        (
            [*FEATURE_ROWS, {"service_movie_id": 40, "tmdb_id": 400, "indices": [], "values": []}],
            "do not cover exactly the service mapping",
        ),
        (
            [{**FEATURE_ROWS[0], "service_movie_id": 20}, *FEATURE_ROWS[1:]],
            "one positive unique row per service movie",
        ),
        (
            [{**FEATURE_ROWS[0], "indices": [0, 9]}, *FEATURE_ROWS[1:]],
            "violates the artifact contract",
        ),
        (
            [{**FEATURE_ROWS[0], "indices": [2, 0]}, *FEATURE_ROWS[1:]],
            "violates the artifact contract",
        ),
        (
            [{**FEATURE_ROWS[0], "values": [0.6, -0.8]}, *FEATURE_ROWS[1:]],
            "violates the artifact contract",
        ),
        (
            [{**FEATURE_ROWS[0], "values": [0.6]}, *FEATURE_ROWS[1:]],
            "content feature row is malformed",
        ),
    ],
)
def test_rejects_invalid_content_features(tmp_path, features, error) -> None:
    with pytest.raises(ArtifactError, match=error):
        _load(_bundle(tmp_path, features=features))


def test_rejects_content_features_with_wrong_physical_types(tmp_path) -> None:
    """The element field name is a writer detail; the element type is contract."""
    wrong_index_type = pa.schema([
        pa.field("service_movie_id", pa.int64()),
        pa.field("tmdb_id", pa.int32()),
        pa.field("indices", pa.list_(pa.int64())),
        pa.field("values", pa.list_(pa.float64())),
    ])

    with pytest.raises(ArtifactError, match="content features has incompatible physical types"):
        _load(_bundle(tmp_path, features_schema=wrong_index_type))
