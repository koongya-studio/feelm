import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
MODULE_PATH = HERE / "verify-consumer-e2e.py"
SPEC = importlib.util.spec_from_file_location("verify_consumer_e2e", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class ConsumerBackendFixtureContractTest(unittest.TestCase):
    """Full-checkout check, required by the main Consumer image test job."""

    def test_personal_gbt_fixtures_match_backend_contract(self):
        root = HERE.parents[1]
        for name in ("gbt-personal-state.json", "gbt-personal-cold-state.json"):
            with self.subTest(fixture=name):
                consumer = root / "pipeline/consumer/tests/fixtures" / name
                backend = root / "backend/src/test/resources" / name
                self.assertEqual(json.loads(consumer.read_text(encoding="utf-8")),
                                 json.loads(backend.read_text(encoding="utf-8")))


class VerifyConsumerE2ETest(unittest.TestCase):
    generation = "550e8400-e29b-41d4-a716-446655440000"

    def values(self, *, version="2", generation=generation, candidate_version=6):
        return [
            version,
            json.dumps(
                {
                    "modelVersion": "model-v1",
                    "candidateVersion": candidate_version,
                    "generationId": generation,
                    "vector": [0.1],
                    "ratingCount": 10,
                    "factorBackedInputCount": 10,
                    "updatedAt": "2026-09-16T00:00:00Z",
                }
            ),
            json.dumps(
                {
                    "modelVersion": "model-v1",
                    "candidateVersion": candidate_version,
                    "generationId": generation,
                    "taste": [
                        {
                            "movieId": 1,
                            "score": 0.9,
                            "reasons": [
                                {
                                    "code": "SHARED_GENRE",
                                    "sourceMovieIds": [2],
                                    "matchedGenreCodes": ["DRAMA"],
                                }
                            ],
                        },
                        {
                            "movieId": 2,
                            "score": 0.8,
                            "reasons": [
                                {
                                    "code": "TOP_RATED_SOURCE",
                                    "sourceMovieIds": [3],
                                    "matchedGenreCodes": [],
                                }
                            ],
                        },
                    ],
                    "discovery": [
                        {
                            "movieId": 3,
                            "score": 0.7,
                            "reasons": [
                                {
                                    "code": "DISCOVERY_AGGREGATE_AFFINITY",
                                    "directionSource": "RATING",
                                    "contributorCount": 1,
                                    "topContributors": [
                                        {"movieId": 2, "relation": "POSITIVE_ALIGNMENT"}
                                    ],
                                    "topContributionCoverage": 1.0,
                                    "viewedCountInGroup": 0,
                                    "affinityRank": 1,
                                    "popularityRankInGroup": 1,
                                }
                            ],
                        },
                        {
                            "movieId": 4,
                            "score": 0.6,
                            "reasons": [
                                {
                                    "code": "DISCOVERY_PERSONAL_SCORE",
                                    "sourceMovieIds": [2],
                                    "matchedGenreCodes": ["DRAMA"],
                                }
                            ],
                        },
                    ],
                    "updatedAt": "2026-09-16T00:00:00Z",
                }
            ),
        ]

    @staticmethod
    def candidates(values):
        return json.loads(values[2])

    def test_valid_snapshot_supports_all_four_reason_codes_without_flavor_code(self):
        result = MODULE.validate_snapshot(self.values(), after_version=1)

        self.assertEqual(result, {"version": 2, "taste": 2, "discovery": 2})

    def test_unchanged_or_partial_snapshot_is_not_ready(self):
        with self.assertRaises(MODULE.NotReady):
            MODULE.validate_snapshot(self.values(), after_version=2)
        with self.assertRaises(MODULE.NotReady):
            MODULE.validate_snapshot(["2", None, None], after_version=1)

    def test_generation_and_candidate_version_must_match_contract(self):
        mismatched = self.values()
        candidates = self.candidates(mismatched)
        candidates["generationId"] = "other-generation"
        mismatched[2] = json.dumps(candidates)
        with self.assertRaisesRegex(MODULE.VerificationError, "generationId"):
            MODULE.validate_snapshot(mismatched, after_version=None)
        with self.assertRaisesRegex(MODULE.VerificationError, "candidateVersion 6"):
            MODULE.validate_snapshot(self.values(candidate_version=5), after_version=None)

    def test_candidate_lists_are_required(self):
        values = self.values()
        candidates = self.candidates(values)
        candidates.pop("taste")
        candidates.pop("discovery")
        candidates["items"] = []
        values[2] = json.dumps(candidates)
        with self.assertRaisesRegex(MODULE.VerificationError, "taste and discovery"):
            MODULE.validate_snapshot(values, after_version=None)

    def test_empty_reasons_are_valid(self):
        values = self.values()
        candidates = self.candidates(values)
        for item in [*candidates["taste"], *candidates["discovery"]]:
            item["reasons"] = []
        values[2] = json.dumps(candidates)

        result = MODULE.validate_snapshot(values, after_version=None)

        self.assertEqual(result, {"version": 2, "taste": 2, "discovery": 2})

    def test_singular_or_non_array_reasons_are_rejected(self):
        values = self.values()
        candidates = self.candidates(values)
        candidates["taste"][0]["reason"] = candidates["taste"][0].pop("reasons")[0]
        values[2] = json.dumps(candidates)
        with self.assertRaisesRegex(MODULE.VerificationError, "singular reason"):
            MODULE.validate_snapshot(values, after_version=None)

        values = self.values()
        candidates = self.candidates(values)
        candidates["taste"][0]["reasons"] = {}
        values[2] = json.dumps(candidates)
        with self.assertRaisesRegex(MODULE.VerificationError, "must be an array"):
            MODULE.validate_snapshot(values, after_version=None)

    def test_reasons_must_contain_objects_with_codes_for_their_list(self):
        values = self.values()
        candidates = self.candidates(values)
        candidates["taste"][0]["reasons"] = ["SHARED_GENRE"]
        values[2] = json.dumps(candidates)
        with self.assertRaisesRegex(MODULE.VerificationError, "contain objects"):
            MODULE.validate_snapshot(values, after_version=None)

        values = self.values()
        candidates = self.candidates(values)
        candidates["discovery"][0]["reasons"][0]["code"] = "SHARED_GENRE"
        values[2] = json.dumps(candidates)
        with self.assertRaisesRegex(MODULE.VerificationError, "invalid for its list"):
            MODULE.validate_snapshot(values, after_version=None)

    def test_source_reason_semantics_are_enforced(self):
        values = self.values()
        candidates = self.candidates(values)
        candidates["taste"][0]["reasons"][0]["matchedGenreCodes"] = []
        values[2] = json.dumps(candidates)
        with self.assertRaisesRegex(MODULE.VerificationError, "SHARED_GENRE"):
            MODULE.validate_snapshot(values, after_version=None)

        values = self.values()
        candidates = self.candidates(values)
        candidates["discovery"][1]["reasons"][0]["sourceMovieIds"] = []
        values[2] = json.dumps(candidates)
        with self.assertRaisesRegex(MODULE.VerificationError, "DISCOVERY_PERSONAL_SCORE"):
            MODULE.validate_snapshot(values, after_version=None)

    def test_reason_codes_reject_fields_from_other_structures(self):
        values = self.values()
        candidates = self.candidates(values)
        candidates["taste"][0]["reasons"][0]["directionSource"] = "RATING"
        values[2] = json.dumps(candidates)
        with self.assertRaisesRegex(MODULE.VerificationError, "SHARED_GENRE reason fields"):
            MODULE.validate_snapshot(values, after_version=None)

        values = self.values()
        candidates = self.candidates(values)
        candidates["discovery"][0]["reasons"][0]["sourceMovieIds"] = [2]
        values[2] = json.dumps(candidates)
        with self.assertRaisesRegex(MODULE.VerificationError, "AGGREGATE_AFFINITY reason fields"):
            MODULE.validate_snapshot(values, after_version=None)

    def test_aggregate_reason_semantics_are_enforced(self):
        values = self.values()
        candidates = self.candidates(values)
        candidates["discovery"][0]["reasons"][0]["topContributionCoverage"] = 1.1
        values[2] = json.dumps(candidates)

        with self.assertRaisesRegex(MODULE.VerificationError, "coverage"):
            MODULE.validate_snapshot(values, after_version=None)

        for contributor_count, contributors, coverage in (
            (0, [], 1.0),
            (2, [], 0.5),
            (2, [{"movieId": 2, "relation": "POSITIVE_ALIGNMENT"}], 1.0),
        ):
            with self.subTest(
                contributor_count=contributor_count,
                contributors=contributors,
                coverage=coverage,
            ):
                values = self.values()
                candidates = self.candidates(values)
                aggregate = candidates["discovery"][0]["reasons"][0]
                aggregate["contributorCount"] = contributor_count
                aggregate["topContributors"] = contributors
                aggregate["topContributionCoverage"] = coverage
                values[2] = json.dumps(candidates)
                with self.assertRaisesRegex(MODULE.VerificationError, "counts|coverage"):
                    MODULE.validate_snapshot(values, after_version=None)

    def test_candidate_order_is_enforced(self):
        values = self.values()
        candidates = self.candidates(values)
        candidates["taste"][1]["score"] = 1.0
        values[2] = json.dumps(candidates)

        with self.assertRaisesRegex(MODULE.VerificationError, "order"):
            MODULE.validate_snapshot(values, after_version=None)

    def test_env_reader_accepts_quoted_url_without_logging_it(self):
        secret_url = "redis://:secret@example.invalid:6379/0"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "consumer.env"
            path.write_text(
                f'# ignored\nCONSUMER_REDIS_URL="{secret_url}"\n',
                encoding="utf-8",
            )
            self.assertEqual(MODULE.env_value(path, "CONSUMER_REDIS_URL"), secret_url)

    def test_redis_url_parsing_decodes_credentials_without_echoing_them(self):
        connection = MODULE.redis_connection("redis://user:p%40ss@redis.internal:6380/3")

        self.assertEqual(connection, ("redis.internal", 6380, "user", "p@ss", 3, False))

    def test_resp_encoder_and_parser_support_mget_shape(self):
        self.assertEqual(
            MODULE.encode_command("GET", "key"),
            b"*2\r\n$3\r\nGET\r\n$3\r\nkey\r\n",
        )
        from io import BytesIO

        response = MODULE.read_response(BytesIO(b"*3\r\n$1\r\n2\r\n$-1\r\n+OK\r\n"))
        self.assertEqual(response, ["2", None, "OK"])


if __name__ == "__main__":
    unittest.main()
