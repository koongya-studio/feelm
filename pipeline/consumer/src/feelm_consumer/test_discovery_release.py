from __future__ import annotations

from types import SimpleNamespace

import numpy as np
import pytest

from feelm_consumer.artifacts import ArtifactError
from feelm_consumer.discovery_popularity import DiscoveryPopularityBundle
from feelm_consumer.discovery_release import validate_discovery_artifacts
from feelm_consumer.ranking import RankedMovie

REVISION = "sha256:" + "a" * 64


def _groups():
    return SimpleNamespace(
        mapping_revision=REVISION,
        service_movie_ids=np.asarray([10, 20, 30], dtype=np.int64),
        tmdb_ids=np.asarray([100, 200, 300], dtype=np.int64),
        group_ids=np.asarray([0, 0, 2], dtype=np.int32),
        group_mapped=np.asarray([True, True, False]),
        group_means=np.zeros((4, 3), dtype=np.float64),
    )


def _popularity(*, tmdb_ids: list[int] | None = None) -> DiscoveryPopularityBundle:
    service_ids = np.asarray([20, 10, 30], dtype=np.int64)
    external_ids = np.asarray(tmdb_ids or [200, 100, 300], dtype=np.int64)
    return DiscoveryPopularityBundle(
        popularity_version="popularity-v1",
        mapping_revision=REVISION,
        candidates=tuple(RankedMovie(int(movie_id), 1.0) for movie_id in service_ids),
        service_movie_ids=service_ids,
        tmdb_ids=external_ids,
    )


def test_validates_exact_catalog_pair_and_reports_group_supply() -> None:
    report = validate_discovery_artifacts(_groups(), _popularity())

    assert report.movie_count == 3
    assert report.mapped_movie_count == 2
    assert report.mapped_group_count == 1
    assert report.groups_with_at_least_ten_movies == 0
    assert report.groups_with_one_to_nine_movies == 1
    assert report.groups_without_mapped_movies == 3


def test_rejects_same_revision_with_different_tmdb_binding() -> None:
    with pytest.raises(ArtifactError, match="catalogs differ"):
        validate_discovery_artifacts(_groups(), _popularity(tmdb_ids=[999, 100, 300]))
