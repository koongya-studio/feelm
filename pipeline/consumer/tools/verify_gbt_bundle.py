"""Load a pinned bundle; optionally audit a read-only CSV of service_movie_id,tmdb_id."""

import argparse
import csv
import json

from feelm_consumer.gbt.artifacts import compare_mapping, load_bundle
from feelm_consumer.gbt.scorer import Scorer


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True)
    parser.add_argument("--expected-revision", required=True)
    parser.add_argument("--live-mapping")
    args = parser.parse_args()
    bundle = load_bundle(args.bundle, expected_revision=args.expected_revision)
    result = {"model_version": bundle.model_version, "serving_revision": bundle.serving_revision,
              "catalog_rows": len(bundle.table), "k0_status": Scorer(bundle).score([]).status}
    if args.live_mapping:
        with open(args.live_mapping, encoding="utf-8", newline="") as stream:
            reader = csv.DictReader(stream)
            if reader.fieldnames != ["service_movie_id", "tmdb_id"]:
                raise ValueError("CSV must contain only service_movie_id,tmdb_id")
            result["live_mapping"] = compare_mapping(bundle.table, ((int(r["service_movie_id"]), int(r["tmdb_id"]))
                                                                     for r in reader))
    print(json.dumps(result))


if __name__ == "__main__":
    main()
