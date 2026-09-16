"""Reload and cross-check a published Discovery group/popularity pair."""

from __future__ import annotations

import argparse
import json
from dataclasses import asdict

from feelm_consumer.discovery_popularity import load_discovery_popularity
from feelm_consumer.discovery_release import validate_discovery_artifacts
from feelm_consumer.group_artifacts import load_group_bundle


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--group-uri", required=True)
    parser.add_argument("--popularity-uri", required=True)
    args = parser.parse_args(argv)

    groups = load_group_bundle(args.group_uri)
    popularity = load_discovery_popularity(
        args.popularity_uri,
        expected_mapping_revision=groups.mapping_revision,
    )
    report = validate_discovery_artifacts(groups, popularity)
    print(json.dumps(asdict(report), ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
