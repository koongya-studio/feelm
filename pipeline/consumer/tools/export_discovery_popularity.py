"""Build the fixed TMDB/KOBIS popularity order used by Discovery candidate selection."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from feelm_consumer.discovery_popularity import export_discovery_popularity


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", required=True, type=Path)
    parser.add_argument("--kobis-population", required=True, type=Path)
    parser.add_argument("--verified-kobis", required=True, type=Path)
    parser.add_argument("--output-root", required=True, type=Path)
    parser.add_argument("--popularity-version", required=True)
    parser.add_argument("--mapping-revision", required=True)
    args = parser.parse_args(argv)
    target = export_discovery_popularity(
        catalog_path=args.catalog,
        kobis_population_path=args.kobis_population,
        verified_kobis_path=args.verified_kobis,
        output_root=args.output_root,
        popularity_version=args.popularity_version,
        mapping_revision=args.mapping_revision,
    )
    print(target)
    return 0


if __name__ == "__main__":
    sys.exit(main())
