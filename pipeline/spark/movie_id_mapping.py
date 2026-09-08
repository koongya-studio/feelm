"""spark-submit entry point for MovieLens preprocessing and movie ID mapping."""

from jobs.batch.movie_id_mapping import main

if __name__ == "__main__":
    raise SystemExit(main())
