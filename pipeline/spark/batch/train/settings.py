"""Configuration for the Spark ALS baseline batch."""

from __future__ import annotations

import argparse
import os
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from decimal import Decimal
from math import isfinite


@dataclass(frozen=True)
class AlsConfig:
    rank: int
    reg_param: float
    max_iter: int


DEFAULT_ALS_CONFIGS = (
    AlsConfig(rank=32, reg_param=0.1, max_iter=10),
    AlsConfig(rank=64, reg_param=0.1, max_iter=10),
    AlsConfig(rank=64, reg_param=0.05, max_iter=10),
    AlsConfig(rank=64, reg_param=0.1, max_iter=15),
)


def _parse_als_config(value: str) -> AlsConfig:
    try:
        rank_text, reg_param_text, max_iter_text = value.split(":")
        config = AlsConfig(
            rank=int(rank_text),
            reg_param=float(reg_param_text),
            max_iter=int(max_iter_text),
        )
    except ValueError as error:
        raise argparse.ArgumentTypeError(
            "ALS config must use rank:regParam:maxIter, for example 64:0.1:10"
        ) from error
    if (
        config.rank <= 0
        or not isfinite(config.reg_param)
        or config.reg_param < 0
        or config.max_iter <= 0
    ):
        raise argparse.ArgumentTypeError(
            "ALS config requires rank > 0, finite regParam >= 0, and maxIter > 0"
        )
    return config


def _child_uri(base_uri: str, child: str) -> str:
    return f"{base_uri.rstrip('/')}/{child}"


@dataclass(frozen=True)
class AlsJobSettings:
    spark_master: str | None
    spark_app_name: str
    input_uri: str | None
    output_uri: str | None
    seed: int
    train_ratio: float
    validation_ratio: float
    positive_rating_threshold: float
    candidate_size: int
    als_configs: tuple[AlsConfig, ...]

    @classmethod
    def from_sources(
        cls,
        environ: Mapping[str, str] | None = None,
        argv: Sequence[str] | None = None,
    ) -> AlsJobSettings:
        environment = os.environ if environ is None else environ
        parser = argparse.ArgumentParser(
            description="Validate inputs and configure the FEELM Spark ALS baseline."
        )
        parser.add_argument(
            "--spark-master", default=environment.get("RECOMMENDATION_SPARK_MASTER")
        )
        parser.add_argument(
            "--spark-app-name",
            default=environment.get("RECOMMENDATION_SPARK_APP_NAME", "feelm-als-baseline"),
        )
        parser.add_argument("--input-uri", default=environment.get("RECOMMENDATION_INPUT_URI"))
        parser.add_argument("--output-uri", default=environment.get("RECOMMENDATION_OUTPUT_URI"))
        parser.add_argument(
            "--seed",
            type=int,
            default=environment.get("RECOMMENDATION_ALS_SEED", "42"),
        )
        parser.add_argument(
            "--train-ratio",
            type=float,
            default=environment.get("RECOMMENDATION_TRAIN_RATIO", "0.8"),
        )
        parser.add_argument(
            "--validation-ratio",
            type=float,
            default=environment.get("RECOMMENDATION_VALIDATION_RATIO", "0.1"),
        )
        parser.add_argument(
            "--positive-rating-threshold",
            type=float,
            default=environment.get("RECOMMENDATION_POSITIVE_RATING_THRESHOLD", "4.0"),
        )
        parser.add_argument(
            "--candidate-size",
            type=int,
            default=environment.get("RECOMMENDATION_CANDIDATE_SIZE", "500"),
        )
        parser.add_argument(
            "--als-config",
            action="append",
            type=_parse_als_config,
            help="Repeatable rank:regParam:maxIter tuple",
        )
        arguments = parser.parse_args(argv)

        configured_values = arguments.als_config
        if configured_values is None and environment.get("RECOMMENDATION_ALS_CONFIGS"):
            configured_values = [
                _parse_als_config(value.strip())
                for value in environment["RECOMMENDATION_ALS_CONFIGS"].split(",")
                if value.strip()
            ]

        settings = cls(
            spark_master=arguments.spark_master,
            spark_app_name=arguments.spark_app_name,
            input_uri=arguments.input_uri,
            output_uri=arguments.output_uri,
            seed=arguments.seed,
            train_ratio=arguments.train_ratio,
            validation_ratio=arguments.validation_ratio,
            positive_rating_threshold=arguments.positive_rating_threshold,
            candidate_size=arguments.candidate_size,
            als_configs=tuple(configured_values or DEFAULT_ALS_CONFIGS),
        )
        settings.validate()
        return settings

    @property
    def test_ratio(self) -> float:
        return float(
            Decimal("1") - Decimal(str(self.train_ratio)) - Decimal(str(self.validation_ratio))
        )

    def validate(self) -> None:
        if not 0 < self.train_ratio < 1:
            raise ValueError("train ratio must be between 0 and 1")
        if not 0 < self.validation_ratio < 1:
            raise ValueError("validation ratio must be between 0 and 1")
        if self.test_ratio <= 0:
            raise ValueError("train and validation ratios must leave a positive test ratio")
        if not 0.5 <= self.positive_rating_threshold <= 5.0:
            raise ValueError("positive rating threshold must be between 0.5 and 5.0")
        if self.candidate_size < 10:
            raise ValueError("candidate size must be at least 10")
        if not self.als_configs:
            raise ValueError("at least one ALS config is required")

    def require_input_uris(self) -> tuple[str, str]:
        if not self.input_uri:
            raise ValueError("input URI is required")
        return (
            _child_uri(self.input_uri, "ratings"),
            _child_uri(self.input_uri, "service-movie-id-map"),
        )
