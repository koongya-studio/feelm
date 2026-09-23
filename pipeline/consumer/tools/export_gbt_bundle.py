"""Export the frozen v26 model plus a whitelist of movie features. No ratings needed."""

import argparse
import json
from pathlib import Path

import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq

from feelm_consumer.gbt.artifacts import sha
from feelm_consumer.gbt.export import export_bundle
from feelm_consumer.gbt.schema import CATALOG_SCHEMA, COEFFICIENT, FEATURES, POLICY, SEMANTICS

MODEL_SHA = "4d035f96f362458f0ce9b749bad4decbd4626cf279a072b6c1acd3b3acf92c25"
SCHEMA_SHA = "c052b00282c647af610d6904c4daaa5a19a16a250e28346fbed079c65fb0fa8e"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("catalog", "references", "model", "schema", "output", "snapshot", "candidate-cutoff"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--model-version", default="gbt-v26-top2-raw")
    args = parser.parse_args()
    model, raw_schema = Path(args.model).read_bytes(), Path(args.schema).read_bytes()
    if sha(model) != MODEL_SHA or sha(raw_schema) != SCHEMA_SHA:
        raise ValueError("Frozen v26 model/schema changed")
    if json.loads(raw_schema) != {"features": FEATURES, "coefficient": COEFFICIENT,
                                  "policy_version": POLICY, "score_semantics": SEMANTICS}:
        raise ValueError("Frozen v26 feature contract changed")
    catalog = pq.read_table(args.catalog, columns=[n for n in CATALOG_SCHEMA.names if n != "eligible_movie"])
    refs = pq.read_table(args.references, columns=["service_movie_id", "tmdb_id", "eligible_movie"])
    for column in ("service_movie_id", "tmdb_id"):
        if not np.array_equal(catalog[column].to_numpy(), refs[column].to_numpy()):
            raise ValueError("Candidate ID axis mismatch")
    columns = [refs[n] if n == "eligible_movie" else catalog[n] for n in CATALOG_SCHEMA.names]
    table = pa.Table.from_arrays(columns, schema=CATALOG_SCHEMA)
    provenance = {"input_snapshot": args.snapshot, "candidate_cutoff": args.candidate_cutoff,
                  "training_rule": POLICY,
                  "source_sha256": {"model": MODEL_SHA, "schema": SCHEMA_SHA,
                                    "catalog": sha(Path(args.catalog).read_bytes()),
                                    "references": sha(Path(args.references).read_bytes())}}
    revision = export_bundle(table=table, model_path=args.model, output=args.output,
                             model_version=args.model_version, provenance=provenance)
    print(json.dumps({"serving_revision": revision, "catalog_rows": len(table)}))


if __name__ == "__main__":
    main()
