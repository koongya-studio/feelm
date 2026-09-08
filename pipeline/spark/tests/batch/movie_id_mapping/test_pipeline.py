from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from pyspark.sql import SparkSession

from batch.prepare.movie_id_mapping.cli import parse_args
from batch.prepare.movie_id_mapping.contracts import (
    MOVIELENS_TMDB_ID_MISSING,
    SERVICE_ONLY_CONTENT_BASED,
    TMDB_ID_AMBIGUOUS,
)
from batch.prepare.movie_id_mapping.io import write_pipeline
from batch.prepare.movie_id_mapping.pipeline import build_pipeline
from batch.prepare.movie_id_mapping.preparation import (
    prepare_links,
    prepare_movielens_movies,
    prepare_ratings,
    prepare_service_movies,
)
from batch.train.inputs import load_training_inputs

FIXTURE_DIRECTORY = Path(__file__).parent / "fixtures"


class MovieIdMappingPipelineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.spark = (
            SparkSession.builder.master("local[1]")
            .appName("movie-id-mapping-test")
            .config("spark.ui.enabled", "false")
            .config("spark.sql.shuffle.partitions", "1")
            .config("spark.sql.session.timeZone", "UTC")
            .config("spark.sql.ansi.enabled", "true")
            .getOrCreate()
        )
        cls.spark.sparkContext.setLogLevel("ERROR")
        cls.ratings_source = cls._read_csv("ratings.csv")
        cls.movies_source = cls._read_csv("movies.csv")
        cls.links_source = cls._read_csv("links.csv")
        cls.service_movies_source = cls._read_csv("service-movies.csv")
        cls.result = build_pipeline(
            cls.ratings_source,
            cls.movies_source,
            cls.links_source,
            cls.service_movies_source,
        )

    @classmethod
    def tearDownClass(cls) -> None:
        cls.spark.stop()

    @classmethod
    def _read_csv(cls, name: str):
        return cls.spark.read.option("header", True).csv(str(FIXTURE_DIRECTORY / name))

    def test_builds_movie_and_service_id_maps(self) -> None:
        result = self.result
        mappings = {
            row["movielens_movie_id"]: row["tmdb_id"] for row in result.movie_id_map.collect()
        }

        self.assertEqual({1: 100, 3: 300}, mappings)
        self.assertNotIn("service_movie_id", result.movie_id_map.columns)
        service_mapping = result.service_movie_id_map.filter("service_movie_id = 10").first()
        self.assertEqual(10, service_mapping["service_movie_id"])
        self.assertEqual(1, service_mapping["movielens_movie_id"])
        self.assertEqual(3, result.service_movie_id_map.count())
        content_based = result.service_movie_id_map.filter("service_movie_id = 20").first()
        self.assertIsNone(content_based["movielens_movie_id"])
        self.assertEqual(
            SERVICE_ONLY_CONTENT_BASED,
            content_based["mapping_status"],
        )
        self.assertEqual(2, result.ratings.count())
        self.assertEqual(3, result.movies.count())
        self.assertEqual(
            1,
            result.validation_report["inputs"]["ratings"]["duplicates_removed"],
        )
        self.assertEqual(
            1,
            result.validation_report["inputs"]["ratings"]["invalid_or_missing"],
        )
        self.assertEqual(
            1,
            result.validation_report["quality"]["ratings_missing_movie_reference"],
        )
        self.assertEqual(2, result.validation_report["mapping"]["matched"])
        self.assertEqual(1, result.validation_report["mapping"]["missing_tmdb_id"])
        self.assertEqual(
            2,
            result.validation_report["service_mapping"]["content_based"],
        )
        self.assertTrue(result.validation_report["all_checks_passed"])

        latest_rating = result.ratings.filter(
            "movielens_user_id = 1 AND movielens_movie_id = 1"
        ).first()
        self.assertEqual(4.5, latest_rating["rating"])
        self.assertEqual(200, latest_rating["rated_at_epoch"])

        missing = result.movies.filter("movielens_movie_id = 2").first()
        self.assertIsNone(missing["tmdb_id"])
        self.assertEqual(MOVIELENS_TMDB_ID_MISSING, missing["mapping_status"])

    def test_marks_duplicate_tmdb_ids_as_ambiguous(self) -> None:
        movies = self.spark.createDataFrame(
            [(1, "First", "Drama"), (2, "Second", "Drama")],
            ["movieId", "title", "genres"],
        )
        links = self.spark.createDataFrame(
            [(1, 100), (2, 100)],
            ["movieId", "tmdbId"],
        )
        ratings = self.spark.createDataFrame(
            [(1, 1, 4.0, 100)],
            ["userId", "movieId", "rating", "timestamp"],
        )
        service_movies = self.spark.createDataFrame(
            [(10, 100)],
            ["service_movie_id", "tmdb_id"],
        )

        result = build_pipeline(ratings, movies, links, service_movies)

        statuses = {row["mapping_status"] for row in result.movie_id_map.collect()}
        self.assertEqual({TMDB_ID_AMBIGUOUS}, statuses)
        self.assertEqual(2, result.validation_report["mapping"]["ambiguous_tmdb_id"])
        self.assertEqual(0, result.validation_report["mapping"]["matched"])
        service_mapping = result.service_movie_id_map.first()
        self.assertIsNone(service_mapping["movielens_movie_id"])
        self.assertEqual(TMDB_ID_AMBIGUOUS, service_mapping["mapping_status"])
        self.assertTrue(result.validation_report["all_checks_passed"])

    def test_writes_mapping_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            output = Path(temporary_directory, "output")
            write_pipeline(self.result, str(output), "errorifexists")

            self.assertEqual(2, self.spark.read.parquet(str(output / "ratings")).count())
            self.assertEqual(3, self.spark.read.parquet(str(output / "movies")).count())
            self.assertEqual(2, self.spark.read.parquet(str(output / "movie-id-map")).count())
            self.assertEqual(
                3,
                self.spark.read.parquet(str(output / "service-movie-id-map")).count(),
            )
            report = self.spark.read.json(str(output / "validation-report")).first()
            self.assertTrue(report["all_checks_passed"])
            self.assertEqual(2, report["outputs"]["movie_id_map"])
            # Verify the actual Parquet output satisfies the downstream ALS contract.
            training_inputs = load_training_inputs(
                self.spark, str(output / "ratings"), str(output / "service-movie-id-map")
            )
            self.assertEqual(2, training_inputs.ratings_count)
            self.assertEqual(3, training_inputs.service_movie_count)
            self.assertEqual(1, training_inputs.matched_service_movie_count)

    def test_cli_requires_backend_export(self) -> None:
        with self.assertRaises(SystemExit):
            parse_args(
                [
                    "--ratings",
                    "ratings.csv",
                    "--movies",
                    "movies.csv",
                    "--links",
                    "links.csv",
                    "--output",
                    "output",
                ]
            )

        arguments = parse_args(
            [
                "--ratings",
                "ratings.csv",
                "--movies",
                "movies.csv",
                "--links",
                "links.csv",
                "--service-movies",
                "service-movies.csv",
                "--output",
                "output",
            ]
        )

        self.assertEqual("links.csv", arguments.links)
        self.assertEqual("service-movies.csv", arguments.service_movies)
        self.assertFalse(hasattr(arguments, "tmdb_movies"))

    def test_rejects_unready_backend_export(self) -> None:
        empty = self.service_movies_source.limit(0)
        duplicate = self.service_movies_source.unionByName(self.service_movies_source.limit(1))

        with self.assertRaisesRegex(ValueError, "export is empty"):
            build_pipeline(
                self.ratings_source,
                self.movies_source,
                self.links_source,
                empty,
            )
        with self.assertRaisesRegex(ValueError, "duplicate rows"):
            build_pipeline(
                self.ratings_source,
                self.movies_source,
                self.links_source,
                duplicate,
            )

    def test_discards_invalid_rating_numbers_with_ansi_enabled(self) -> None:
        invalid = [
            ("bad", "1", "4.0", "100"),
            ("2147483648", "1", "4.0", "100"),
            ("1", "bad", "4.0", "100"),
            ("1", "2147483648", "4.0", "100"),
            ("1", "1", "bad", "100"),
            ("1", "1", "4.0", "bad"),
            ("1", "1", "4.0", "9223372036854775808"),
            ("1", "1", " ", "100"),
            ("1", "1", "NaN", "100"),
            ("1", "1", "Infinity", "100"),
            ("1", "1", "1e309", "100"),
        ]
        source = self.spark.createDataFrame(
            [("1", "1", "4.5", "200"), (" 2 ", "1", "0.5", "0"), *invalid],
            ["userId", "movieId", "rating", "timestamp"],
        )
        prepared = prepare_ratings(source)
        self.addCleanup(prepared.frame.unpersist)

        self.assertEqual("true", self.spark.conf.get("spark.sql.ansi.enabled"))
        self.assertEqual(13, prepared.raw_count)
        self.assertEqual(2, prepared.valid_count)
        self.assertEqual(11, prepared.invalid_count)
        self.assertEqual(0, prepared.duplicate_count)
        self.assertEqual(
            [(1, 1, 4.5, 200), (2, 1, 0.5, 0)],
            [tuple(row) for row in prepared.frame.orderBy("movielens_user_id").collect()],
        )

    def test_discards_invalid_movie_ids_and_preserves_multiple_genres(self) -> None:
        source = self.spark.createDataFrame(
            [
                ("1", "A", "Drama|Comedy"),
                ("bad", "B", "Drama"),
                ("2147483648", "C", "Drama"),
                (" ", "D", "Drama"),
            ],
            ["movieId", "title", "genres"],
        )
        prepared = prepare_movielens_movies(source)
        self.addCleanup(prepared.frame.unpersist)

        self.assertEqual(4, prepared.raw_count)
        self.assertEqual(1, prepared.valid_count)
        self.assertEqual(3, prepared.invalid_count)
        self.assertEqual([(1, "A", "Comedy|Drama")], [tuple(r) for r in prepared.frame.collect()])

    def test_keeps_unusable_tmdb_ids_as_unmapped_with_ansi_enabled(self) -> None:
        source = self.spark.createDataFrame(
            [
                ("1", "100"),
                ("2", "bad"),
                ("3", "2147483648"),
                ("4", None),
                ("bad", "200"),
                ("2147483648", "200"),
            ],
            "movieId string, tmdbId string",
        )
        prepared = prepare_links(source)
        self.addCleanup(prepared.frame.unpersist)

        self.assertEqual(6, prepared.raw_count)
        self.assertEqual(4, prepared.valid_count)
        self.assertEqual(2, prepared.invalid_count)
        self.assertEqual(
            {1: 100, 2: None, 3: None, 4: None},
            {row["movielens_movie_id"]: row["tmdb_id"] for row in prepared.frame.collect()},
        )

    def test_rejects_invalid_backend_numbers_with_ansi_enabled(self) -> None:
        for bad_row in [
            ("bad", "200"),
            ("9223372036854775808", "200"),
            ("20", "bad"),
            ("20", "2147483648"),
            (None, "200"),
            ("0", "200"),
        ]:
            with self.subTest(row=bad_row):
                source = self.spark.createDataFrame(
                    [("10", "100"), bad_row],
                    "service_movie_id string, tmdb_id string",
                )
                with self.assertRaisesRegex(ValueError, "contains null, non-numeric"):
                    prepare_service_movies(source)

    def test_uses_latest_rating_and_collapses_identical_latest_rows(self) -> None:
        rows = [
            ("1", "1", "1.0", "100"),
            ("1", "1", "5.0", "100"),
            ("1", "1", "4.5", "200"),
            ("1", "1", "4.5", "200"),
        ]
        for ordered_rows in [rows, rows[::-1]]:
            with self.subTest(rows=ordered_rows):
                source = self.spark.createDataFrame(
                    ordered_rows, ["userId", "movieId", "rating", "timestamp"]
                )
                prepared = prepare_ratings(source)
                self.addCleanup(prepared.frame.unpersist)
                self.assertEqual([(1, 1, 4.5, 200)], [tuple(r) for r in prepared.frame.collect()])
                self.assertEqual(3, prepared.duplicate_count)

    def test_rejects_conflicting_latest_ratings_in_both_input_orders(self) -> None:
        rows = [("1", "1", "1.0", "100"), ("1", "1", "5.0", "100")]
        for ordered_rows in [rows, rows[::-1]]:
            with self.subTest(rows=ordered_rows):
                source = self.spark.createDataFrame(
                    ordered_rows, ["userId", "movieId", "rating", "timestamp"]
                )
                with self.assertRaisesRegex(ValueError, "latest ratings has conflicting rating"):
                    prepare_ratings(source)

    def test_normalizes_genre_sets_and_prefers_known_metadata(self) -> None:
        rows = [
            ("1", "A", " Drama | Comedy | Drama "),
            ("1", "A", "Comedy|Drama"),
            ("1", "A", None),
            ("1", "A", "(no genres listed)"),
            ("2", "B", " | "),
            ("2", "B", "(no genres listed)"),
        ]
        for ordered_rows in [rows, rows[::-1]]:
            with self.subTest(rows=ordered_rows):
                source = self.spark.createDataFrame(
                    ordered_rows, "movieId string, title string, genres string"
                )
                prepared = prepare_movielens_movies(source)
                self.addCleanup(prepared.frame.unpersist)
                self.assertEqual(
                    [(1, "A", "Comedy|Drama"), (2, "B", None)],
                    [tuple(r) for r in prepared.frame.orderBy("movielens_movie_id").collect()],
                )
                self.assertEqual(4, prepared.duplicate_count)

    def test_rejects_conflicting_known_genre_sets_in_both_input_orders(self) -> None:
        rows = [("1", "A", "Drama|Comedy"), ("1", "A", "Drama|Action")]
        for ordered_rows in [rows, rows[::-1]]:
            with self.subTest(rows=ordered_rows):
                source = self.spark.createDataFrame(ordered_rows, ["movieId", "title", "genres"])
                with self.assertRaisesRegex(ValueError, "conflicting movielens_genres_raw"):
                    prepare_movielens_movies(source)


if __name__ == "__main__":
    unittest.main()
