from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from feelm_consumer.artifacts import ArtifactError
from feelm_consumer.discovery_popularity import DiscoveryPopularityBundle
from feelm_consumer.group_artifacts import GroupBundle


@dataclass(frozen=True)
class DiscoveryArtifactReport:
    mapping_revision: str
    movie_count: int
    mapped_movie_count: int
    mapped_group_count: int
    groups_with_at_least_ten_movies: int
    groups_with_one_to_nine_movies: int
    groups_without_mapped_movies: int


def validate_discovery_artifacts(
    groups: GroupBundle,
    popularity: DiscoveryPopularityBundle,
) -> DiscoveryArtifactReport:
    """Validate the exact catalog pair before Discovery is enabled.

    A revision label is necessary but not sufficient: an exporter invocation
    could accidentally attach the right label to a catalog with different ID
    bindings.  Compare every service/TMDb pair so that such a release fails
    before Kafka, PostgreSQL, or Redis clients are created.
    """
    if groups.mapping_revision != popularity.mapping_revision:
        raise ArtifactError("Discovery group and popularity mapping revisions differ")
    if groups.service_movie_ids.size != popularity.service_movie_ids.size:
        raise ArtifactError("Discovery group and popularity catalogs differ")

    order = np.argsort(popularity.service_movie_ids, kind="stable")
    if not np.array_equal(groups.service_movie_ids, popularity.service_movie_ids[order]) or not np.array_equal(
        groups.tmdb_ids, popularity.tmdb_ids[order]
    ):
        raise ArtifactError("Discovery group and popularity catalogs differ")

    mapped_counts = np.bincount(
        groups.group_ids[groups.group_mapped],
        minlength=groups.group_means.shape[0],
    )
    mapped_group_count = int(np.count_nonzero(mapped_counts))
    return DiscoveryArtifactReport(
        mapping_revision=groups.mapping_revision,
        movie_count=int(groups.service_movie_ids.size),
        mapped_movie_count=int(groups.group_mapped.sum()),
        mapped_group_count=mapped_group_count,
        groups_with_at_least_ten_movies=int(np.count_nonzero(mapped_counts >= 10)),
        groups_with_one_to_nine_movies=int(np.count_nonzero((mapped_counts > 0) & (mapped_counts < 10))),
        groups_without_mapped_movies=int(mapped_counts.size - mapped_group_count),
    )
