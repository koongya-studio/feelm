"""Load and validate a content-artifacts-v2 serving bundle.

The bundle is optional: without ``CONSUMER_CONTENT_URI`` the Consumer keeps its
ALS-only behaviour.  When it is configured, every generation marker must match the
ALS model exactly, because mixing a content bundle with a model built from another
mapping generation would score movies against the wrong catalog.

``content_revision`` is deliberately not recomputed here.  The contract defines it
over Spark's ``to_json`` number formatting, so only a Spark reader can reproduce
it; a non-Spark consumer compares the recorded string and validates structure.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pyarrow as pa

from feelm_consumer.artifacts import ArtifactError, _dataset, _require_physical_types, _revision

CONTRACT_VERSION = "content-artifacts-v2"
FEATURE_RULE = "structured-4group-v1"
ELIGIBILITY_RULE = "language-year-genre-v1"
PRIOR_BINS = 10

FEATURES_TYPES = {
    "service_movie_id": pa.int64(),
    "tmdb_id": pa.int32(),
    "indices": pa.list_(pa.int32()),
    "values": pa.list_(pa.float64()),
}


@dataclass(frozen=True)
class ContentBundle:
    """Sparse content vectors of every service movie, in one vocabulary space."""

    content_version: str
    content_revision: str
    vocabulary_size: int
    prior_midrank: np.ndarray
    service_movie_ids: np.ndarray
    """Sorted service movie IDs; row i of the matrix belongs to ``service_movie_ids[i]``."""
    row_of_nonzero: np.ndarray
    column_of_nonzero: np.ndarray
    weight_of_nonzero: np.ndarray
    supported: np.ndarray
    """Per row: whether the movie has a non-empty vector, i.e. a content score exists."""

    @property
    def movie_count(self) -> int:
        return int(self.service_movie_ids.size)

    def row_index(self, service_movie_id: int) -> int | None:
        position = int(np.searchsorted(self.service_movie_ids, service_movie_id))
        if position >= self.service_movie_ids.size or self.service_movie_ids[position] != service_movie_id:
            return None
        return position

    def nonzeros(self, row: int) -> tuple[np.ndarray, np.ndarray]:
        """Return the vocabulary indices and weights of one movie's vector."""
        start, end = np.searchsorted(self.row_of_nonzero, [row, row + 1])
        return self.column_of_nonzero[start:end], self.weight_of_nonzero[start:end]

    def scores(self, profile: np.ndarray) -> np.ndarray:
        """Return the profile's dot product with every movie vector (one CSR pass)."""
        if profile.shape != (self.vocabulary_size,):
            raise ArtifactError("content profile does not match the vocabulary")
        products = self.weight_of_nonzero * profile[self.column_of_nonzero]
        return np.bincount(self.row_of_nonzero, weights=products, minlength=self.movie_count)


def _metadata_row(uri: str) -> dict[str, object]:
    table = _dataset(uri, "metadata")
    if table.num_rows != 1:
        raise ArtifactError("content metadata must contain exactly one row")
    return dict(table.to_pylist()[0])


def _prior(metadata: dict[str, object]) -> np.ndarray:
    """Validate the rating prior, including that it carries an actual distribution.

    A midrank array that is merely finite and ordered can still be meaningless —
    all zeros, all ones, or values outside [0, 1] — and that silently turns every
    user's content profile into an unusable one at serving time.
    """
    probability, values = metadata.get("prior_probability"), metadata.get("prior_midrank")
    for candidate in (probability, values):
        if not isinstance(candidate, list) or len(candidate) != PRIOR_BINS:
            raise ArtifactError("content prior must have ten bins")
    weights = np.asarray(probability, dtype=np.float64)
    prior = np.asarray(values, dtype=np.float64)
    if not np.isfinite(weights).all() or np.any(weights < 0) or abs(float(weights.sum()) - 1.0) > 1e-9:
        raise ArtifactError("content prior probability must be a distribution")
    if not np.isfinite(prior).all() or np.any(np.diff(prior) < 0):
        raise ArtifactError("content prior must be finite and ordered")
    if prior[0] < 0 or prior[-1] > 1 or prior[-1] <= prior[0]:
        raise ArtifactError("content prior must span an increasing range inside [0, 1]")
    # The contract defines the midrank as cumsum(p) - 0.5p over the same
    # probabilities, so the two arrays must describe one distribution.  Checking
    # them separately would accept a real probability paired with the v1
    # placeholder midrank, which the contract forbids for serving.
    if not np.allclose(prior, np.cumsum(weights) - 0.5 * weights, rtol=0, atol=1e-9):
        raise ArtifactError("content prior midrank does not match its probability")
    # The v1 writer shipped a flat [0.1] * 10 placeholder, which the contract
    # forbids for serving.  A prior measured over millions of real ratings is
    # never exactly uniform, so an exactly flat one identifies the placeholder.
    if np.all(weights == weights[0]):
        raise ArtifactError("content prior is the placeholder distribution")
    counts = (metadata.get("prior_user_count"), metadata.get("prior_rating_count"))
    if any(not isinstance(value, int) or isinstance(value, bool) or value <= 0 for value in counts):
        raise ArtifactError("content prior counts are missing or invalid")
    if counts[1] < counts[0]:
        raise ArtifactError("content prior rating count is below its user count")
    return prior


def load_content(
    uri: str,
    *,
    mapping_revision: str,
    movie_metadata_revision: str,
    input_snapshot: str,
    service_movie_ids: set[int],
) -> ContentBundle:
    """Load one content bundle that belongs to the same generation as the ALS model."""
    metadata = _metadata_row(uri)
    if metadata.get("contract_version") != CONTRACT_VERSION:
        raise ArtifactError("unsupported content artifact")
    if metadata.get("feature_rule") != FEATURE_RULE or metadata.get("eligibility_rule") != ELIGIBILITY_RULE:
        raise ArtifactError("unsupported content feature rule")
    if (
        _revision(metadata.get("mapping_revision"), "content mapping revision") != mapping_revision
        or _revision(metadata.get("movie_metadata_revision"), "content movie metadata revision")
        != movie_metadata_revision
        or metadata.get("input_snapshot") != input_snapshot
    ):
        raise ArtifactError("content bundle belongs to another artifact generation")
    content_version, content_revision = metadata.get("content_version"), metadata.get("content_revision")
    if not isinstance(content_version, str) or not content_version:
        raise ArtifactError("invalid content version")
    # The runtime Consumer only reads an atomically published final directory.
    # Writer staging paths are mutable and can disappear during rename, so accepting
    # one here would bypass the publication boundary.
    directory = uri.rstrip("/").rsplit("/", 1)[-1]
    if directory != content_version:
        raise ArtifactError("content version does not match its path")
    _revision(content_revision, "content revision")
    vocabulary_size = metadata.get("vocabulary_size")
    if not isinstance(vocabulary_size, int) or isinstance(vocabulary_size, bool) or vocabulary_size <= 0:
        raise ArtifactError("invalid content vocabulary size")
    prior_midrank = _prior(metadata)

    for name in ("service_movie_count", "supported_movie_count"):
        if name not in metadata:
            raise ArtifactError("content metadata has missing columns")

    vocabulary = _dataset(uri, "vocabulary")
    if vocabulary.num_rows != vocabulary_size:
        raise ArtifactError("content vocabulary size does not match metadata")

    features = _dataset(uri, "features")
    required = set(FEATURES_TYPES)
    if not required.issubset(features.column_names):
        raise ArtifactError("content features have missing columns")
    _require_physical_types(features, FEATURES_TYPES, "content features")
    return _build_bundle(
        features,
        metadata=metadata,
        content_version=content_version,
        content_revision=str(content_revision),
        vocabulary_size=vocabulary_size,
        prior_midrank=prior_midrank,
        service_movie_ids=service_movie_ids,
    )


def _build_bundle(
    features: pa.Table,
    *,
    metadata: dict[str, object],
    content_version: str,
    content_revision: str,
    vocabulary_size: int,
    prior_midrank: np.ndarray,
    service_movie_ids: set[int],
) -> ContentBundle:
    """Flatten the sparse vectors into CSR arrays after validating every row."""
    ids = features.column("service_movie_id").to_numpy(zero_copy_only=False)
    order = np.argsort(ids, kind="stable")
    sorted_ids = ids[order].astype(np.int64, copy=False)
    if sorted_ids.size == 0 or np.any(np.diff(sorted_ids) <= 0) or sorted_ids[0] <= 0:
        raise ArtifactError("content features must have one positive unique row per service movie")
    if set(sorted_ids.tolist()) != service_movie_ids:
        raise ArtifactError("content features do not cover exactly the service mapping")

    index_lists = features.column("indices").to_pylist()
    value_lists = features.column("values").to_pylist()
    rows: list[int] = []
    columns: list[int] = []
    weights: list[float] = []
    supported = np.zeros(sorted_ids.size, dtype=bool)
    for row, source in enumerate(order.tolist()):
        indices, values = index_lists[source], value_lists[source]
        if indices is None or values is None or len(indices) != len(values):
            raise ArtifactError("content feature row is malformed")
        if any(index is None for index in indices) or any(value is None for value in values):
            raise ArtifactError("content feature row is malformed")
        if not indices:
            continue
        index_array = np.asarray(indices, dtype=np.int64)
        value_array = np.asarray(values, dtype=np.float64)
        if (
            np.any(np.diff(index_array) <= 0)
            or index_array[0] < 0
            or index_array[-1] >= vocabulary_size
            or not np.isfinite(value_array).all()
            or np.any(value_array <= 0)
        ):
            raise ArtifactError("content feature row violates the artifact contract")
        supported[row] = True
        rows.extend([row] * len(indices))
        columns.extend(index_array.tolist())
        weights.extend(value_array.tolist())

    # Both counts are required: without them the only metadata-to-features cross
    # check would silently switch itself off.
    service_count, supported_count = metadata.get("service_movie_count"), metadata.get("supported_movie_count")
    for value in (service_count, supported_count):
        if not isinstance(value, int) or isinstance(value, bool) or value < 0:
            raise ArtifactError("content metadata counts are missing or invalid")
    if sorted_ids.size != service_count or int(supported.sum()) != supported_count:
        raise ArtifactError("content movie counts do not match metadata")
    return ContentBundle(
        content_version=content_version,
        content_revision=content_revision,
        vocabulary_size=vocabulary_size,
        prior_midrank=prior_midrank,
        service_movie_ids=sorted_ids,
        row_of_nonzero=np.asarray(rows, dtype=np.int64),
        column_of_nonzero=np.asarray(columns, dtype=np.int64),
        weight_of_nonzero=np.asarray(weights, dtype=np.float64),
        supported=supported,
    )
