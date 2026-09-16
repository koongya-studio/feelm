from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

import psycopg

from feelm_consumer.flavor_assignments import sync_flavor_assignments
from feelm_consumer.group_artifacts import load_group_bundle


def _arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate and apply S15P21E106-439 popcorn flavor assignments."
    )
    parser.add_argument("--bundle-uri", required=True, help="439 group bundle URI")
    parser.add_argument(
        "--database-url-env",
        default="CONSUMER_POSTGRES_DSN",
        help="name of the environment variable containing the PostgreSQL DSN",
    )
    parser.add_argument(
        "--expected-mapping-revision",
        required=True,
        help="approved sha256 mapping revision; prevents applying another catalog mapping",
    )
    parser.add_argument("--apply", action="store_true", help="commit updates; default is rollback-only dry-run")
    parser.add_argument("--report", type=Path, help="optional JSON report output path")
    return parser.parse_args()


def main() -> int:
    args = _arguments()
    database_url = os.environ.get(args.database_url_env)
    if not database_url:
        raise SystemExit(f"missing database URL environment variable: {args.database_url_env}")

    bundle = load_group_bundle(args.bundle_uri, args.expected_mapping_revision)
    with psycopg.connect(database_url) as connection:
        report = sync_flavor_assignments(connection, bundle, apply=args.apply)

    encoded = json.dumps(report.to_dict(), ensure_ascii=False, indent=2, sort_keys=True)
    print(encoded)
    if args.report is not None:
        args.report.write_text(encoded + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
