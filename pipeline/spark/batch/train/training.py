"""Single-configuration Spark ALS model training."""

from __future__ import annotations

from pyspark.ml.recommendation import ALS, ALSModel
from pyspark.sql import DataFrame

from batch.train.settings import AlsConfig

USER_COLUMN = "movielens_user_id"
ITEM_COLUMN = "movielens_movie_id"
RATING_COLUMN = "rating"


def train_als_model(
    ratings: DataFrame,
    config: AlsConfig,
    seed: int,
) -> ALSModel:
    """Train one explicit-feedback ALS model with the supplied settings."""
    estimator = ALS(
        userCol=USER_COLUMN,
        itemCol=ITEM_COLUMN,
        ratingCol=RATING_COLUMN,
        rank=config.rank,
        regParam=config.reg_param,
        maxIter=config.max_iter,
        seed=seed,
        implicitPrefs=False,
        nonnegative=False,
    )
    return estimator.fit(ratings)
