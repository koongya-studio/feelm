"""Frozen v26 feature order; no raw TMDB count feature or quality gate."""

import pyarrow as pa

AXES = ("collection", "director", "cast", "country_genre", "keyword")
SIGNS = ("pos", "strong_pos", "weak_pos", "neg")
DIRECT_FIELDS = ("series", "director", "cast_sources", "cast_people", "cast_units", "units", "anchor")
FEATURES = (
    ["history_log1p"]
    + [f"{axis}_{field}" for axis in AXES for field in ("known", *(f"{s}_log1p" for s in SIGNS))]
    + ["history_rating_mean", "history_rating_std", "history_positive_share", "history_negative_share"]
    + [f"strict_{side}_{name}" for side in ("positive", "negative") for name in DIRECT_FIELDS]
    + ["reach_log1p", "reach_known", "tmdb_rating_raw_0_1", "tmdb_rating_known"]
    + [f"history_{side}_reach_{key}" for side in ("positive", "negative")
       for key in ("mean_log1p", "known_share", "count_log1p", "present")]
    + [f"candidate_{side}_reach_{key}" for side in ("positive", "negative") for key in ("cross", "distance")]
    + [f"{axis}_{side}_x_reach" for axis in AXES for side in ("pos", "neg")]
    + ["verified_kobis_member"]
)
COEFFICIENT = 0.0007732457348003972
SEMANTICS = "RECOMMENDATION_POLICY_SCORE_NOT_EXPECTED_STARS"
CONTRACT = "gbt-model-artifacts-v1"
TRANSFORM = "v26-max70-kobis-flag-71-v1"
POLICY = "tmdb-top2pct-2294-verified-kobis-preserve-v25"
CATALOG_SCHEMA = pa.schema([
    pa.field("service_movie_id", pa.int64(), nullable=False),
    pa.field("tmdb_id", pa.int64(), nullable=False),
    *[pa.field(n, pa.list_(pa.int64())) for n in
      ("collection_ids", "director_ids", "top5_cast_ids", "genre_ids", "keyword_ids")],
    *[pa.field(n, pa.list_(pa.string())) for n in ("origin_country_codes", "production_country_codes")],
    pa.field("tmdb_vote_count", pa.float64()),
    pa.field("tmdb_vote_average", pa.float64()),
    pa.field("kobis_movie_code", pa.string()),
    pa.field("kobis_link_status", pa.string()),
    pa.field("kobis_value_valid", pa.bool_()),
    pa.field("kobis_audience_cumulative", pa.float64()),
    pa.field("eligible_movie", pa.bool_(), nullable=False),
])
