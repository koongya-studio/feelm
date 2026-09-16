from __future__ import annotations

from types import SimpleNamespace

import pytest

from batch.train.artifacts import SERVING
from streaming.party import artifacts as subject


class Frame:
    def __init__(self, rows):
        self.rows = rows
        self.mapping_status = Column()

    def filter(self, condition):
        assert condition is True
        return self

    def select(self, *columns):
        return Frame([{column: row[column] for column in columns} for row in self.rows])

    def collect(self):
        return self.rows


class Column:
    def __eq__(self, value):
        return value == "MATCHED"


class Reader:
    def __init__(self, frame):
        self.frame = frame
        self.uri = None

    def parquet(self, uri):
        self.uri = uri
        return self.frame


class Spark:
    def __init__(self, frame):
        self.read = Reader(frame)


def install_artifacts(monkeypatch, *, stage=SERVING, revision="sha256:" + "a" * 64):
    item_factors = Frame(
        [{"movielens_movie_id": 7, "features": [0.1, 0.2]}]
    )
    loaded = SimpleNamespace(
        metadata={"artifact_stage": stage, "mapping_revision": revision},
        item_factors=item_factors,
    )
    monkeypatch.setattr(subject, "load_als_artifacts", lambda *args: loaded)


def test_loads_revision_matched_serving_lookups(monkeypatch):
    revision = "sha256:" + "a" * 64
    mapping_frame = Frame(
        [{"service_movie_id": 100, "movielens_movie_id": 7, "mapping_status": "MATCHED"}]
    )
    spark = Spark(mapping_frame)
    install_artifacts(monkeypatch, revision=revision)
    monkeypatch.setattr(
        subject,
        "validate_service_movie_map",
        lambda frame: (frame, 1, 1, revision),
    )

    result = subject.load_party_runtime_artifacts(
        spark,
        model_root_uri="hdfs:///models",
        model_version="als-v1",
        service_movie_map_uri="hdfs:///mapping",
    )

    assert spark.read.uri == "hdfs:///mapping"
    assert result.model_version == "als-v1"
    assert result.service_to_model_movie_ids == {100: 7}
    assert result.model_item_factors == {7: (0.1, 0.2)}


def test_rejects_mapping_from_a_different_revision(monkeypatch):
    mapping_frame = Frame([])
    install_artifacts(monkeypatch)
    monkeypatch.setattr(
        subject,
        "validate_service_movie_map",
        lambda frame: (frame, 1, 1, "sha256:" + "b" * 64),
    )

    with pytest.raises(ValueError, match="revision"):
        subject.load_party_runtime_artifacts(
            Spark(mapping_frame),
            model_root_uri="hdfs:///models",
            model_version="als-v1",
            service_movie_map_uri="hdfs:///mapping",
        )


def test_rejects_non_serving_artifact(monkeypatch):
    install_artifacts(monkeypatch, stage="MODEL_SELECTION")

    with pytest.raises(ValueError, match="SERVING"):
        subject.load_party_runtime_artifacts(
            Spark(Frame([])),
            model_root_uri="hdfs:///models",
            model_version="als-v1",
            service_movie_map_uri="hdfs:///mapping",
        )
