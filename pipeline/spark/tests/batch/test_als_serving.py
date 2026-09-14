import pytest

from jobs.batch.als_serving import validate_immutable_artifact_uri


@pytest.mark.parametrize("alias", ["latest", "current"])
def test_serving_publication_rejects_movable_artifact_uris(alias: str) -> None:
    with pytest.raises(ValueError, match="immutable versioned artifact URI"):
        validate_immutable_artifact_uri(
            f"hdfs://namenode/artifacts/movie-metadata/{alias}",
            "--movie-metadata-uri",
        )


def test_serving_publication_accepts_explicit_versioned_artifact_uri() -> None:
    uri = "hdfs://namenode/artifacts/movie-metadata/snapshot-20260914-v1"
    assert validate_immutable_artifact_uri(uri, "--movie-metadata-uri") == uri
