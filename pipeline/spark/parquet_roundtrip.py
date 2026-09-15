"""spark-submit entry point for the Parquet runtime check."""

from jobs.batch.parquet_roundtrip import main

if __name__ == "__main__":
    main()
