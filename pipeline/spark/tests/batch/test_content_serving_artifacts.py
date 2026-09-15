from __future__ import annotations

import numpy as np
import pytest
from pyspark.sql import SparkSession
from pyspark.sql import functions as F

from batch.content import serving
from batch.content.features import (
    build_content_vector,
    build_vocabulary,
    user_equal_prior,
    validate_metadata_collection,
)
from batch.content.serving import (
    CONTRACT_VERSION,
    FEATURES_SCHEMA,
    METADATA_SCHEMA,
    VOCABULARY_SCHEMA,
    build_content_artifacts,
    compute_content_revision,
    load_content_artifacts,
    save_content_artifacts,
)
from batch.prepare.movie_metadata import build_movie_metadata

MAPPING_REVISION = "sha256:" + "1" * 64
TMDB_REVISION = "sha256:" + "2" * 64
OTHER_REVISION = "sha256:" + "9" * 64
SNAPSHOT = "fixture-snapshot-1"
INPUT_URI = "file:///snapshots/movie-mapping/fixture-v1"
VERSION = "content-fixture-v1"

SNAPSHOT_ROWS = [
    # id, title, lang, year, runtime, genre_ids, directors, cast, cast order, keywords, eligible
    (100, "A", "en", 1995, 81, [18, 35], [77], [5, 9, 7, 11, 12], [0, 1, 2, 4, 5], [1, 3], True),
    (200, "B", None, None, None, [], [], [], [], [], False),
    (300, "C", "ko", 2020, None, [18], [], [], [], [8], True),
    (400, "D", "en", 2001, 120, [28], [77], [5], [0], [], True),
]
MAPPING_ROWS = [
    (1000, 100, 1, "MATCHED"),
    (2000, 200, None, "SERVICE_ONLY_CONTENT_BASED"),
    (3000, 300, None, "TMDB_ID_AMBIGUOUS"),
    (4000, 400, None, "SERVICE_ONLY_CONTENT_BASED"),
]
RATING_ROWS = [(1, 1, 4.0), (1, 2, 4.0), (1, 3, 3.5), (2, 1, 5.0), (3, 1, 0.5), (3, 2, 1.0)]


@pytest.fixture(scope="module")
def spark():
    session = (
        SparkSession.builder.master("local[1]")
        .appName("content-serving-artifacts-test")
        .config("spark.ui.enabled", "false")
        .config("spark.sql.shuffle.partitions", "2")
        .getOrCreate()
    )
    session.sparkContext.setLogLevel("ERROR")
    yield session
    session.stop()


def _snapshot(spark, snapshot_rows, mapping_revision):
    return spark.createDataFrame(
        [(*row, TMDB_REVISION, mapping_revision) for row in snapshot_rows],
        "id int, title string, original_language string, release_year int, "
        "runtime_minutes int, genre_ids array<int>, director_ids array<int>, "
        "top5_cast_ids array<int>, top5_cast_source_order array<int>, keyword_ids array<int>, "
        "feature_eligible boolean, tmdb_snapshot_revision string, mapping_revision string",
    ).withColumn("genres", F.array().cast("array<struct<id:int,name:string>>"))


def _inputs(
    spark,
    *,
    mapping_rows=MAPPING_ROWS,
    snapshot_rows=SNAPSHOT_ROWS,
    revisions=None,
    snapshot_mapping_revision=MAPPING_REVISION,
    metadata_mapping_rows=None,
    metadata_snapshot_rows=None,
):
    revisions = revisions or [MAPPING_REVISION] * len(mapping_rows)
    mapping = spark.createDataFrame(
        [(*row, revision) for row, revision in zip(mapping_rows, revisions, strict=True)],
        "service_movie_id long, tmdb_id int, movielens_movie_id int, mapping_status string, "
        "mapping_revision string",
    )
    snapshot = _snapshot(spark, snapshot_rows, snapshot_mapping_revision)
    ratings = spark.createDataFrame(
        RATING_ROWS, "movielens_user_id int, movielens_movie_id int, rating float"
    )
    metadata = build_movie_metadata(
        spark.createDataFrame(
            [(row[0], row[1]) for row in (metadata_mapping_rows or mapping_rows)],
            "service_movie_id long, tmdb_id int",
        ),
        _snapshot(spark, metadata_snapshot_rows or snapshot_rows, snapshot_mapping_revision),
        SNAPSHOT,
    )
    return ratings, mapping, snapshot, metadata


def _build(spark, **overrides):
    ratings, mapping, snapshot, metadata = _inputs(spark)
    arguments = {
        "ratings": ratings,
        "service_mapping": mapping,
        "tmdb_snapshot": snapshot,
        "movie_metadata": metadata,
        "input_uri": INPUT_URI,
        "mapping_revision": MAPPING_REVISION,
        "tmdb_snapshot_revision": TMDB_REVISION,
        "movie_metadata_revision": metadata.first()["movie_metadata_revision"],
        "input_snapshot": SNAPSHOT,
        "content_version": VERSION,
        "built_at_utc": "2026-09-14T00:00:00Z",
    }
    arguments.update(overrides)
    return build_content_artifacts(**arguments)


def _expected_315():
    rows = [
        {
            "tmdb_id": row[0],
            "original_language": row[2],
            "release_year": row[3],
            "runtime_minutes": row[4],
            "genre_ids": row[5],
            "director_ids": row[6],
            "top5_cast_ids": row[7],
            "top5_cast_source_order": row[8],
            "keyword_ids": row[9],
            "feature_eligible": row[10],
        }
        for row in SNAPSHOT_ROWS
    ]
    normalized = validate_metadata_collection(rows)
    vectors = [build_content_vector(row) for row in normalized]
    vocabulary = build_vocabulary(vectors)
    encoded = {
        row["tmdb_id"]: build_content_vector(vector, vocabulary)
        for row, vector in zip(normalized, vectors, strict=True)
    }
    return vocabulary, encoded


def test_content_vectors_match_315_feature_functions(spark) -> None:
    build = _build(spark)
    vocabulary, encoded = _expected_315()

    assert {row["token"]: row["feature_index"] for row in build.vocabulary.collect()} == vocabulary
    features = {row["tmdb_id"]: row for row in build.features.collect()}
    assert len(features) == len(MAPPING_ROWS)
    for tmdb_id, (indices, values, unknown) in encoded.items():
        assert unknown == 0
        assert features[tmdb_id]["indices"] == list(indices)
        np.testing.assert_allclose(features[tmdb_id]["values"], values, rtol=1e-12, atol=0)
    assert features[200]["indices"] == [] and features[200]["values"] == []
    assert {row["service_movie_id"] for row in features.values()} == {1000, 2000, 3000, 4000}


def test_content_prior_is_user_equal_over_all_ratings(spark) -> None:
    build = _build(spark)
    histograms = {}
    for user, _, rating in RATING_ROWS:
        histograms.setdefault(user, [0.0] * 10)[int(rating * 2) - 1] += 1
    probability, midrank = user_equal_prior([histograms[user] for user in sorted(histograms)])

    np.testing.assert_allclose(build.metadata["prior_probability"], probability, atol=1e-12)
    np.testing.assert_allclose(build.metadata["prior_midrank"], midrank, atol=1e-12)
    assert build.metadata["prior_user_count"] == 3
    assert build.metadata["prior_rating_count"] == len(RATING_ROWS)


def test_content_metadata_links_revisions_and_counts(spark, tmp_path) -> None:
    build = _build(spark, implementation_revision="fixture-revision")
    output_uri = tmp_path.as_uri()
    version_uri = save_content_artifacts(spark, build, output_uri)
    loaded = load_content_artifacts(spark, output_uri, VERSION)
    metadata = loaded.metadata

    assert version_uri == f"{output_uri}/content/{VERSION}"
    assert sorted(child.name for child in (tmp_path / "content" / VERSION).iterdir()) == [
        "features",
        "metadata",
        "vocabulary",
    ]
    assert metadata["contract_version"] == CONTRACT_VERSION
    assert metadata["mapping_revision"] == MAPPING_REVISION
    assert metadata["movie_metadata_revision"] == build.metadata["movie_metadata_revision"]
    assert metadata["tmdb_snapshot_revision"] == TMDB_REVISION
    assert metadata["input_snapshot"] == SNAPSHOT
    assert metadata["input_uri"] == INPUT_URI
    assert metadata["service_movie_count"] == 4
    assert metadata["supported_movie_count"] == 3
    assert metadata["vocabulary_size"] == len(_expected_315()[0])
    assert metadata["implementation_revision"] == "fixture-revision"
    assert metadata["content_revision"] == compute_content_revision(
        loaded.features, loaded.vocabulary, list(metadata["prior_probability"])
    )


@pytest.mark.parametrize(
    "case",
    [
        "argument_revision",
        "two_revisions",
        "missing_snapshot_row",
        "metadata_snapshot",
        "snapshot_revision",
        "snapshot_mapping_revision",
        "metadata_matched_only",
        "metadata_other_tmdb_snapshot",
        "movable_input_uri",
    ],
)
def test_content_build_rejects_inconsistent_inputs(spark, case) -> None:
    ratings, mapping, snapshot, metadata = _inputs(spark)
    arguments = {
        "ratings": ratings,
        "service_mapping": mapping,
        "tmdb_snapshot": snapshot,
        "movie_metadata": metadata,
        "input_uri": INPUT_URI,
        "mapping_revision": MAPPING_REVISION,
        "tmdb_snapshot_revision": TMDB_REVISION,
        "movie_metadata_revision": metadata.first()["movie_metadata_revision"],
        "input_snapshot": SNAPSHOT,
        "content_version": VERSION,
    }
    if case == "argument_revision":
        arguments["mapping_revision"] = OTHER_REVISION
        message = "mapping revision does not match"
    elif case == "two_revisions":
        _, arguments["service_mapping"], _, _ = _inputs(
            spark, revisions=[MAPPING_REVISION] * 3 + ["sha256:" + "3" * 64]
        )
        message = "mapping revision does not match"
    elif case == "missing_snapshot_row":
        arguments["tmdb_snapshot"] = snapshot.filter(F.col("id") != 400)
        message = "every service movie must have a TMDb snapshot row"
    elif case == "metadata_snapshot":
        arguments["input_snapshot"] = "other-snapshot"
        message = "movie metadata input snapshot does not match"
    elif case == "snapshot_revision":
        # The TMDb snapshot the caller names is not the one that was read.
        arguments["tmdb_snapshot_revision"] = OTHER_REVISION
        message = "TMDb snapshot revision does not match"
    elif case == "snapshot_mapping_revision":
        # A TMDb snapshot built over a different service catalog generation.
        _, _, arguments["tmdb_snapshot"], _ = _inputs(
            spark, snapshot_mapping_revision=OTHER_REVISION
        )
        message = "TMDb snapshot was not built from the mapping snapshot"
    elif case == "metadata_matched_only":
        # The pre-416 movie-metadata shape: MATCHED service movies only.
        _, _, _, older = _inputs(spark, metadata_mapping_rows=MAPPING_ROWS[:1])
        arguments["movie_metadata"] = older
        arguments["movie_metadata_revision"] = older.first()["movie_metadata_revision"]
        message = "movie metadata was not built from this service mapping and TMDb snapshot"
    elif case == "metadata_other_tmdb_snapshot":
        # Same service movies, titles from a different TMDb snapshot generation.
        _, _, _, other = _inputs(
            spark,
            metadata_snapshot_rows=[
                (row[0], f"{row[1]} (rerun)", *row[2:]) for row in SNAPSHOT_ROWS
            ],
        )
        arguments["movie_metadata"] = other
        arguments["movie_metadata_revision"] = other.first()["movie_metadata_revision"]
        message = "movie metadata was not built from this service mapping and TMDb snapshot"
    else:
        arguments["input_uri"] = "file:///snapshots/movie-mapping/latest"
        message = "content input URI must be an immutable versioned path"

    with pytest.raises(ValueError, match=message):
        build_content_artifacts(**arguments)


def test_content_publication_rejects_invalid_inputs_without_partial_output(
    spark, tmp_path, monkeypatch
) -> None:
    build = _build(spark)
    output_uri = tmp_path.as_uri()
    content_root = tmp_path / "content"

    def reject(*_args, **_kwargs):
        raise ValueError("injected reload failure")

    with monkeypatch.context() as patch:
        patch.setattr(serving, "_load_content_artifacts_at_uri", reject)
        with pytest.raises(ValueError, match="injected reload failure"):
            save_content_artifacts(spark, build, output_uri)
    # Nothing at all is left behind, whatever the temporary path is named.
    assert list(content_root.iterdir()) == []

    save_content_artifacts(spark, build, output_uri)
    with pytest.raises(FileExistsError, match="already exists"):
        save_content_artifacts(spark, build, output_uri)
    assert load_content_artifacts(spark, output_uri, VERSION).metadata["service_movie_count"] == 4
    assert [child.name for child in content_root.iterdir()] == [VERSION]


def _write_bundle(spark, root, features, vocabulary, metadata_rows) -> None:
    version_root = root / "content" / VERSION
    spark.createDataFrame(features, FEATURES_SCHEMA).write.parquet(str(version_root / "features"))
    spark.createDataFrame(vocabulary, VOCABULARY_SCHEMA).write.parquet(
        str(version_root / "vocabulary")
    )
    spark.createDataFrame(metadata_rows, METADATA_SCHEMA).coalesce(1).write.parquet(
        str(version_root / "metadata")
    )


@pytest.mark.parametrize(
    "case",
    [
        "two_metadata_rows",
        "duplicate_service_id",
        "duplicate_tmdb_id",
        "index_out_of_range",
        "vocabulary_gap",
        "revision",
        "prior_midrank",
        "movable_input_snapshot",
        "input_uri",
        "built_at_utc",
        "spark_version",
    ],
)
def test_content_reader_rejects_corrupt_bundle(spark, tmp_path, case) -> None:
    build = _build(spark)
    features = [
        tuple(row) for row in build.features.select(*FEATURES_SCHEMA.fieldNames()).collect()
    ]
    vocabulary = [
        tuple(row) for row in build.vocabulary.select(*VOCABULARY_SCHEMA.fieldNames()).collect()
    ]
    metadata = tuple(build.metadata[name] for name in METADATA_SCHEMA.fieldNames())
    metadata_rows = [metadata]
    if case == "two_metadata_rows":
        metadata_rows = [metadata, metadata]
        message = "exactly one row"
    elif case in ("duplicate_service_id", "duplicate_tmdb_id"):
        # Keep the row count, the supported count and the other ID unique so a
        # single uniqueness check is the only thing that can reject the bundle.
        supported = next(row for row in features if row[2])
        replaced = next(row for row in features if row[2] and row[0] != supported[0])
        duplicated = (
            (supported[0], replaced[1], replaced[2], replaced[3])
            if case == "duplicate_service_id"
            else (replaced[0], supported[1], replaced[2], replaced[3])
        )
        features = [duplicated if row[0] == replaced[0] else row for row in features]
        message = "do not match metadata counts or are duplicated"
    elif case == "index_out_of_range":
        target = next(index for index, row in enumerate(features) if row[2])
        service_id, tmdb_id, indices, values = features[target]
        features[target] = (service_id, tmdb_id, [*indices[:-1], len(vocabulary) + 5], values)
        message = "violate the artifact contract"
    elif case == "vocabulary_gap":
        vocabulary = [
            (len(vocabulary) if index == 0 else index, token) for index, token in vocabulary
        ]
        message = "contiguous"
    else:
        field, replacement, message = {
            "revision": (
                "content_revision",
                "sha256:" + "0" * 64,
                "content revision does not match",
            ),
            "prior_midrank": (
                "prior_midrank",
                [value + 0.01 for value in build.metadata["prior_midrank"]],
                "prior midrank does not match its probability",
            ),
            "movable_input_snapshot": (
                "input_snapshot",
                "latest",
                "input snapshot must be an immutable identifier",
            ),
            "input_uri": (
                "input_uri",
                "file:///snapshots/movie-mapping/latest",
                "input_uri must be an immutable versioned path",
            ),
            "built_at_utc": (
                "built_at_utc",
                "2026-09-14 00:00:00",
                "built_at_utc must use",
            ),
            "spark_version": ("spark_version", "", "spark_version must not be empty"),
        }[case]
        values = list(metadata)
        values[METADATA_SCHEMA.fieldNames().index(field)] = replacement
        metadata_rows = [tuple(values)]
    _write_bundle(spark, tmp_path, features, vocabulary, metadata_rows)

    with pytest.raises(ValueError, match=message):
        load_content_artifacts(spark, tmp_path.as_uri(), VERSION)
