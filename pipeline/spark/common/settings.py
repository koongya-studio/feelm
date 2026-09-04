"""Shared, environment-independent batch job settings."""

from __future__ import annotations

import argparse
import os
from collections.abc import Mapping, Sequence
from dataclasses import dataclass


@dataclass(frozen=True)
class JobSettings:
    """Runtime settings shared by local and distributed Spark jobs."""

    spark_master: str | None
    spark_app_name: str
    input_uri: str | None
    output_uri: str | None

    @classmethod
    def from_sources(
        cls,
        environ: Mapping[str, str] | None = None,
        argv: Sequence[str] | None = None,
    ) -> JobSettings:
        environment = os.environ if environ is None else environ
        parser = argparse.ArgumentParser(description="Run a FEELM recommendation Spark job.")
        parser.add_argument("--spark-master")
        parser.add_argument("--spark-app-name")
        parser.add_argument("--input-uri")
        parser.add_argument("--output-uri")
        arguments = parser.parse_args(argv)

        return cls(
            spark_master=arguments.spark_master or environment.get("RECOMMENDATION_SPARK_MASTER"),
            spark_app_name=arguments.spark_app_name
            or environment.get("RECOMMENDATION_SPARK_APP_NAME", "feelm-recommendation"),
            input_uri=arguments.input_uri or environment.get("RECOMMENDATION_INPUT_URI"),
            output_uri=arguments.output_uri or environment.get("RECOMMENDATION_OUTPUT_URI"),
        )

    def require_io_uris(self) -> tuple[str, str]:
        if not self.input_uri or not self.output_uri:
            raise ValueError("input and output URIs are required")
        return self.input_uri, self.output_uri
