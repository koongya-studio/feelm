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


class VerifyConsumerE2ETest(unittest.TestCase):
    generation = "550e8400-e29b-41d4-a716-446655440000"

    def values(self, *, version="2", generation=generation, candidate_version=4):
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
                            "reason": {
                                "code": "SHARED_GENRE",
                                "sourceMovieIds": [2],
                                "matchedGenreCodes": ["DRAMA"],
                            },
                        }
                    ],
                    "discovery": [],
                    "updatedAt": "2026-09-16T00:00:00Z",
                }
            ),
        ]

    def test_valid_snapshot_requires_newer_version_and_same_generation(self):
        result = MODULE.validate_snapshot(self.values(), after_version=1)

        self.assertEqual(result, {"version": 2, "taste": 1, "discovery": 0})

    def test_unchanged_or_partial_snapshot_is_not_ready(self):
        with self.assertRaises(MODULE.NotReady):
            MODULE.validate_snapshot(self.values(), after_version=2)
        with self.assertRaises(MODULE.NotReady):
            MODULE.validate_snapshot(["2", None, None], after_version=1)

    def test_generation_and_candidate_version_must_match_contract(self):
        mismatched = self.values()
        mismatched[2] = json.dumps(
            {
                "candidateVersion": 4,
                "generationId": "other-generation",
                "modelVersion": "model-v1",
                "taste": [],
                "discovery": [],
                "updatedAt": "2026-09-16T00:00:00Z",
            }
        )
        with self.assertRaisesRegex(MODULE.VerificationError, "generationId"):
            MODULE.validate_snapshot(mismatched, after_version=None)
        with self.assertRaisesRegex(MODULE.VerificationError, "candidateVersion 4"):
            MODULE.validate_snapshot(
                self.values(candidate_version=3),
                after_version=None,
            )

    def test_candidate_lists_are_required(self):
        values = self.values()
        values[2] = json.dumps(
            {
                "candidateVersion": 4,
                "generationId": self.generation,
                "modelVersion": "model-v1",
                "items": [],
                "updatedAt": "2026-09-16T00:00:00Z",
            }
        )
        with self.assertRaisesRegex(MODULE.VerificationError, "taste and discovery"):
            MODULE.validate_snapshot(values, after_version=None)

    def test_invalid_candidate_shape_is_rejected(self):
        values = self.values()
        candidates = json.loads(values[2])
        candidates["taste"][0]["reason"]["sourceMovieIds"] = []
        values[2] = json.dumps(candidates)

        with self.assertRaisesRegex(MODULE.VerificationError, "reason fields"):
            MODULE.validate_snapshot(values, after_version=None)

    def test_reason_semantics_and_candidate_order_are_enforced(self):
        values = self.values()
        candidates = json.loads(values[2])
        candidates["taste"][0]["reason"]["matchedGenreCodes"] = []
        values[2] = json.dumps(candidates)
        with self.assertRaisesRegex(MODULE.VerificationError, "SHARED_GENRE"):
            MODULE.validate_snapshot(values, after_version=None)

        values = self.values()
        candidates = json.loads(values[2])
        candidates["taste"].append(
            {
                "movieId": 2,
                "score": 1.0,
                "reason": {
                    "code": "TOP_RATED_SOURCE",
                    "sourceMovieIds": [3],
                    "matchedGenreCodes": [],
                },
            }
        )
        values[2] = json.dumps(candidates)
        with self.assertRaisesRegex(MODULE.VerificationError, "order"):
            MODULE.validate_snapshot(values, after_version=None)

    def test_env_reader_accepts_quoted_url_without_logging_it(self):
        secret_url = "redis://:secret@example.invalid:6379/0"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "consumer.env"
            path.write_text(
                f"# ignored\nCONSUMER_REDIS_URL=\"{secret_url}\"\n",
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
