"""Load the immutable ALS and movie-ID artifacts used by party streaming."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass

from batch.train.artifacts import SERVING, load_als_artifacts
from batch.train.inputs import MATCHED, validate_service_movie_map


@dataclass(frozen=True)
class PartyRuntimeArtifacts:
    """Driver-side lookup tables pinned to one serving model version."""

    model_version: str
    mapping_revision: str
    service_to_model_movie_ids: Mapping[int, int]
    model_item_factors: Mapping[int, Sequence[float]]


def load_party_runtime_artifacts(
    spark: object,
    *,
    model_root_uri: str,
    model_version: str,
    service_movie_map_uri: str,
) -> PartyRuntimeArtifacts:
    """Load and cross-check the serving model and its service-ID mapping."""
    artifacts = load_als_artifacts(spark, model_root_uri, model_version)
    if artifacts.metadata["artifact_stage"] != SERVING:
        raise ValueError("party streaming requires a SERVING ALS artifact")

    service_movie_map, _, _, mapping_revision = validate_service_movie_map(
        spark.read.parquet(service_movie_map_uri)
    )
    if mapping_revision != artifacts.metadata["mapping_revision"]:
        raise ValueError("service movie mapping revision does not match the ALS artifact")

    mapping = {
        int(row["service_movie_id"]): int(row["movielens_movie_id"])
        for row in service_movie_map.filter(service_movie_map.mapping_status == MATCHED)
        .select("service_movie_id", "movielens_movie_id")
        .collect()
    }
    factors = {
        int(row["movielens_movie_id"]): tuple(float(value) for value in row["features"])
        for row in artifacts.item_factors.select("movielens_movie_id", "features").collect()
    }
    if not mapping:
        raise ValueError("party streaming service movie mapping is empty")
    if not factors:
        raise ValueError("party streaming item factors are empty")
    return PartyRuntimeArtifacts(
        model_version=model_version,
        mapping_revision=mapping_revision,
        service_to_model_movie_ids=mapping,
        model_item_factors=factors,
    )
