import importlib.util
import json
from pathlib import Path
import unittest
from urllib.parse import parse_qs, urlsplit


SCRIPT = Path(__file__).with_name("verify-monitoring-deployment.py")
SPEC = importlib.util.spec_from_file_location("verify_monitoring_deployment", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FakeResponse:
    def __init__(self, payload):
        self.payload = payload if isinstance(payload, bytes) else json.dumps(payload).encode()

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self, *_args):
        return self.payload


def healthy_series():
    result = []
    for job, count in MODULE.EXPECTED_TARGETS.items():
        for index in range(count):
            result.append({"metric": {"job": job, "instance": str(index)}, "value": [1, "1"]})
    return result


def opener_with(series=None, grafana_database="ok"):
    target_series = healthy_series() if series is None else series

    def open_url(url, timeout):
        assert timeout == 5
        parsed = urlsplit(url)
        if parsed.path == "/-/ready":
            return FakeResponse(b"Prometheus Server is Ready.")
        if parsed.path == "/api/health":
            return FakeResponse({"database": grafana_database})
        if parsed.path == "/api/v1/query":
            query = parse_qs(parsed.query)["query"][0]
            assert query == 'up{job=~"backend|node|kafka|postgres|redis"}'
            return FakeResponse({"status": "success", "data": {"result": target_series}})
        raise AssertionError(url)

    return open_url


class MonitoringDeploymentVerificationTest(unittest.TestCase):
    def test_healthy_services_and_all_required_targets_pass(self):
        self.assertEqual(MODULE.verify(opener=opener_with()), MODULE.EXPECTED_TARGETS)

    def test_down_or_missing_target_fails_with_counts(self):
        series = healthy_series()
        series = [item for item in series if item["metric"]["job"] != "kafka"]
        with self.assertRaisesRegex(MODULE.VerificationError, "kafka=0/1"):
            MODULE.verify(opener=opener_with(series=series))

        series = healthy_series()
        next(item for item in series if item["metric"]["job"] == "redis")["value"][1] = "0"
        with self.assertRaisesRegex(MODULE.VerificationError, "redis=0/1"):
            MODULE.verify(opener=opener_with(series=series))

    def test_wrong_target_cardinality_fails(self):
        series = healthy_series() + [
            {"metric": {"job": "node", "instance": "extra"}, "value": [1, "1"]}
        ]
        with self.assertRaisesRegex(MODULE.VerificationError, "node=3/2"):
            MODULE.verify(opener=opener_with(series=series))

    def test_grafana_database_failure_stops_before_target_query(self):
        with self.assertRaisesRegex(MODULE.VerificationError, "Grafana database"):
            MODULE.verify(opener=opener_with(grafana_database="fail"))

    def test_only_loopback_http_urls_are_accepted(self):
        for value in (
            "https://127.0.0.1:9090",
            "http://example.test:9090",
            "http://user:password@127.0.0.1:9090",
        ):
            with self.subTest(value=value), self.assertRaises(MODULE.VerificationError):
                MODULE.validate_base_url(value, "Prometheus")

    def test_retry_recovers_after_transient_failures(self):
        calls = []

        def verifier(_prometheus, _grafana):
            calls.append(1)
            if len(calls) < 3:
                raise MODULE.VerificationError("not ready")
            return MODULE.EXPECTED_TARGETS

        sleeps = []
        result = MODULE.verify_with_retries(
            3,
            0.25,
            "http://127.0.0.1:9090",
            "http://127.0.0.1:3000",
            verifier,
            sleeps.append,
        )
        self.assertEqual(result, MODULE.EXPECTED_TARGETS)
        self.assertEqual(sleeps, [0.25, 0.25])


if __name__ == "__main__":
    unittest.main()
