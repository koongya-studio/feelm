"""spark-submit wrapper for the ALS input manifest generator."""

from jobs.batch.als_input_manifest import main

if __name__ == "__main__":
    raise SystemExit(main())
