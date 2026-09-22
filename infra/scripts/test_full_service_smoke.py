import importlib.util
import json
import sys
from argparse import Namespace
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest


SCRIPT = Path(__file__).with_name("full-service-smoke.py")
SPEC = importlib.util.spec_from_file_location("full_service_smoke", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class FullServiceSmokeTest(unittest.TestCase):
    def test_all_steps_pass(self):
        def factory(_args, _run_id):
            yield "nginx_https", lambda: "ok"
            yield "frontend", lambda: "ok"

        results, failure = MODULE.execute(Namespace(), factory)
        self.assertIsNone(failure)
        self.assertEqual([item.status for item in results], ["PASS", "PASS"])

    def test_first_failure_selects_recovery_and_later_checks_continue(self):
        visited = []

        def fail():
            visited.append("backend")
            raise MODULE.SmokeError("token=private")

        def factory(_args, _run_id):
            yield "backend_health", fail
            yield "postgresql", lambda: visited.append("postgres") or "ok"

        results, failure = MODULE.execute(Namespace(), factory)
        self.assertEqual(failure, "backend_health")
        self.assertEqual(visited, ["backend", "postgres"])
        self.assertEqual(results[0].detail, "token=<redacted>")
        self.assertTrue(MODULE.RECOVERY[failure].startswith("backend:"))

    def test_report_has_no_secret_and_records_recovery(self):
        results = [MODULE.Result("redis", "FAIL", 3, MODULE.redact("password=hunter2"))]
        with TemporaryDirectory() as directory:
            path = Path(directory) / "report.json"
            MODULE.write_report(path, results, "redis")
            payload = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(payload["first_failure"], "redis")
        self.assertIn("deploy-redis.sh --recover", payload["recovery"])
        self.assertNotIn("hunter2", json.dumps(payload))

    def test_remote_host_rejects_shell_metacharacters(self):
        with self.assertRaises(MODULE.SmokeError):
            MODULE.remote("host;shutdown", "true")

    def test_public_endpoint_requires_https_without_credentials(self):
        self.assertEqual(MODULE.public_base_url("https://example.test/"), "https://example.test")
        for value in ("http://example.test", "https://user:secret@example.test"):
            with self.subTest(value=value), self.assertRaises(MODULE.SmokeError):
                MODULE.public_base_url(value)

    def test_spark_requires_two_distinct_alive_workers(self):
        class Response:
            def __enter__(self): return self
            def __exit__(self, *_args): return False
            def read(self):
                return json.dumps({"workers": [
                    {"state": "ALIVE", "host": "node-a"},
                    {"state": "ALIVE", "host": "node-b"},
                ]}).encode()

        self.assertIn("2 ALIVE", MODULE._spark("http://spark", lambda *_args, **_kwargs: Response()))


if __name__ == "__main__":
    unittest.main()
