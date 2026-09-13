"""Configuration for the Spark ALS baseline batch."""

from __future__ import annotations

import argparse
import os
import posixpath
import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal
from math import isfinite
from urllib.parse import unquote, urlsplit
from uuid import uuid4


@dataclass(frozen=True)
class AlsConfig:
    rank: int
    reg_param: float
    max_iter: int


@dataclass(frozen=True)
class AlsRunContext:
    output_uri: str
    input_manifest_uri: str
    input_snapshot: str
    run_id: str
    implementation_revision: str


DEFAULT_ALS_CONFIGS = (
    AlsConfig(rank=32, reg_param=0.1, max_iter=10),
    AlsConfig(rank=64, reg_param=0.1, max_iter=10),
    AlsConfig(rank=64, reg_param=0.05, max_iter=10),
    AlsConfig(rank=64, reg_param=0.1, max_iter=15),
)
DEFAULT_USER_CHUNK_SIZE = 100


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
    mode: str
    spark_master: str | None
    spark_app_name: str
    input_uri: str | None
    output_uri: str | None
    seed: int
    train_ratio: float
    validation_ratio: float
    positive_rating_threshold: float
    candidate_size: int
    user_chunk_size: int
    als_configs: tuple[AlsConfig, ...]
    input_manifest_uri: str | None
    input_snapshot: str | None
    run_id: str | None
    implementation_revision: str | None

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
        parser.add_argument("--mode", choices=("validate", "run"), default="validate")
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
            "--user-chunk-size",
            type=int,
            default=DEFAULT_USER_CHUNK_SIZE,
        )
        parser.add_argument(
            "--als-config",
            action="append",
            type=_parse_als_config,
            help="Repeatable rank:regParam:maxIter tuple",
        )
        parser.add_argument("--input-snapshot")
        parser.add_argument("--input-manifest-uri")
        parser.add_argument("--run-id")
        parser.add_argument("--implementation-revision")
        arguments = parser.parse_args(argv)

        configured_values = arguments.als_config
        if configured_values is None and environment.get("RECOMMENDATION_ALS_CONFIGS"):
            configured_values = [
                _parse_als_config(value.strip())
                for value in environment["RECOMMENDATION_ALS_CONFIGS"].split(",")
                if value.strip()
            ]

        settings = cls(
            mode=arguments.mode,
            spark_master=arguments.spark_master,
            spark_app_name=arguments.spark_app_name,
            input_uri=arguments.input_uri,
            output_uri=arguments.output_uri,
            seed=arguments.seed,
            train_ratio=arguments.train_ratio,
            validation_ratio=arguments.validation_ratio,
            positive_rating_threshold=arguments.positive_rating_threshold,
            candidate_size=arguments.candidate_size,
            user_chunk_size=arguments.user_chunk_size,
            als_configs=tuple(configured_values or DEFAULT_ALS_CONFIGS),
            input_manifest_uri=arguments.input_manifest_uri,
            input_snapshot=arguments.input_snapshot,
            run_id=arguments.run_id or (_new_run_id() if arguments.mode == "run" else None),
            implementation_revision=arguments.implementation_revision,
        )
        settings.validate()
        if settings.mode == "run":
            settings.require_input_uris()
            settings.require_run_context()
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
        if self.user_chunk_size <= 0:
            raise ValueError("user chunk size must be positive")
        if not self.als_configs:
            raise ValueError("at least one ALS config is required")
        if not -(2**63) <= self.seed < 2**63:
            raise ValueError("seed must be a signed 64-bit integer")
        if len(set(self.als_configs)) != len(self.als_configs):
            raise ValueError("duplicate ALS candidate config")

    def require_input_uris(self) -> tuple[str, str]:
        if not self.input_uri:
            raise ValueError("input URI is required")
        return (
            _child_uri(self.input_uri, "ratings"),
            _child_uri(self.input_uri, "service-movie-id-map"),
        )

    def require_run_context(self) -> AlsRunContext:
        if not self.output_uri:
            raise ValueError("output URI is required")
        if not self.input_manifest_uri:
            raise ValueError("input manifest URI is required")
        if not self.input_snapshot:
            raise ValueError("input snapshot is required")
        if self.input_snapshot.casefold() == "latest":
            raise ValueError("input snapshot must be an immutable identifier")
        if not self.implementation_revision:
            raise ValueError("implementation revision is required")
        if not self.input_uri:
            raise ValueError("input URI is required")
        validate_storage_uri(self.input_uri, "input")
        validate_storage_uri(self.output_uri, "output")
        validate_storage_uri(self.input_manifest_uri, "input manifest")
        if _paths_overlap(self.input_uri, self.output_uri):
            raise ValueError("input and output paths must not overlap")
        if _paths_overlap(self.input_manifest_uri, self.output_uri):
            raise ValueError("input manifest and output paths must not overlap")
        run_id = self.run_id or _new_run_id()
        if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,127}", run_id):
            raise ValueError("run ID has an invalid format")
        return AlsRunContext(
            output_uri=self.output_uri,
            input_manifest_uri=self.input_manifest_uri,
            input_snapshot=self.input_snapshot,
            run_id=run_id,
            implementation_revision=self.implementation_revision,
        )


def _new_run_id() -> str:
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    return f"als-{timestamp}-{uuid4().hex[:12]}"


def validate_storage_uri(uri: str, label: str) -> None:
    parsed = urlsplit(uri)
    if parsed.scheme not in {"file", "hdfs"}:
        raise ValueError(f"{label} URI requires an explicit file or hdfs scheme")
    if parsed.username is not None or parsed.password is not None:
        raise ValueError(f"{label} URI must not contain credentials")
    if parsed.query or parsed.fragment:
        raise ValueError(f"{label} URI must not contain a query or fragment")
    if not parsed.path:
        raise ValueError(f"{label} URI path is required")
    if not parsed.path.startswith("/"):
        raise ValueError(f"{label} URI requires an explicit absolute path")
    if parsed.scheme == "file" and parsed.hostname not in {None, "", "localhost"}:
        raise ValueError(f"{label} file URI authority must be empty or localhost")
    if parsed.scheme == "hdfs" and not parsed.hostname:
        raise ValueError(f"{label} hdfs URI requires an explicit authority")


def _paths_overlap(left: str, right: str) -> bool:
    left_location, left_path = _canonical_storage_location(left)
    right_location, right_path = _canonical_storage_location(right)
    if left_location != right_location:
        return False
    return (
        left_path == right_path
        or _is_path_within(left_path, right_path)
        or _is_path_within(right_path, left_path)
    )


def _canonical_storage_location(uri: str) -> tuple[tuple[str, str, int | None], str]:
    parsed = urlsplit(uri)
    scheme = parsed.scheme.casefold() or "file"
    if scheme == "file":
        authority = (parsed.hostname or "localhost").casefold()
        if authority == "localhost":
            authority = "localhost"
        path = os.path.abspath(unquote(parsed.path))
        return (scheme, authority, None), posixpath.normpath(path)
    hostname = (parsed.hostname or "").casefold()
    port = parsed.port or 8020
    return (scheme, hostname, port), posixpath.normpath(unquote(parsed.path))


def _is_path_within(candidate: str, parent: str) -> bool:
    return parent == "/" or candidate.startswith(parent.rstrip("/") + "/")
