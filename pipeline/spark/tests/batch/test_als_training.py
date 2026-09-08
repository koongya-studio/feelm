import math

import pytest
from pyspark.ml.recommendation import ALSModel
from pyspark.sql import SparkSession

from batch.train import training
from batch.train.settings import AlsConfig
from tests.fixtures.als_baseline import RATING_ROWS, RATINGS_SCHEMA


@pytest.fixture(scope="module")
def spark() -> SparkSession:
    session = (
        SparkSession.builder.master("local[1]")
        .appName("als-training-test")
        .config("spark.ui.enabled", "false")
        .config("spark.sql.shuffle.partitions", "1")
        .getOrCreate()
    )
    session.sparkContext.setLogLevel("ERROR")
    yield session
    session.stop()


def test_applies_explicit_als_configuration(monkeypatch: pytest.MonkeyPatch) -> None:
    ratings = object()
    trained_model = object()
    captured_parameters: dict[str, object] = {}

    class FakeAls:
        def __init__(self, **parameters: object) -> None:
            captured_parameters.update(parameters)

        def fit(self, frame: object) -> object:
            assert frame is ratings
            return trained_model

    monkeypatch.setattr(training, "ALS", FakeAls)

    result = training.train_als_model(
        ratings,  # type: ignore[arg-type]
        config=AlsConfig(rank=7, reg_param=0.25, max_iter=3),
        seed=123,
    )

    assert result is trained_model
    assert captured_parameters == {
        "userCol": "movielens_user_id",
        "itemCol": "movielens_movie_id",
        "ratingCol": "rating",
        "rank": 7,
        "regParam": 0.25,
        "maxIter": 3,
        "seed": 123,
        "implicitPrefs": False,
        "nonnegative": False,
    }


def test_trains_model_and_keeps_all_movielens_movies(spark: SparkSession) -> None:
    ratings = spark.createDataFrame(RATING_ROWS, RATINGS_SCHEMA)

    model = training.train_als_model(
        ratings,
        config=AlsConfig(rank=3, reg_param=0.1, max_iter=2),
        seed=42,
    )

    assert isinstance(model, ALSModel)
    assert model.rank == 3
    assert {row["id"] for row in model.userFactors.select("id").collect()} == set(range(1, 7))
    assert {row["id"] for row in model.itemFactors.select("id").collect()} == set(range(1, 15))
    assert model.itemFactors.filter("id IN (13, 14)").count() == 2
    assert model.userFactors.filter("size(features) != 3").count() == 0
    assert model.itemFactors.filter("size(features) != 3").count() == 0

    predictions = model.transform(ratings)
    assert predictions.count() == len(RATING_ROWS)
    prediction_rows = predictions.select("prediction").collect()
    assert all(math.isfinite(row["prediction"]) for row in prediction_rows)
