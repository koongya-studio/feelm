import importlib.util
import json
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("verify-distributed-observability.py")
SPEC = importlib.util.spec_from_file_location("verify_distributed_observability", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class FakeResponse:
    def __init__(self, body):
        self.body = body if isinstance(body, bytes) else body.encode()

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self):
        return self.body


def healthy_opener(url, timeout):
    assert timeout == 5
    if url.endswith(":9308/metrics"):
        return FakeResponse("# HELP kafka_brokers\nkafka_brokers 1\n")
    if url.endswith(":9100/metrics"):
        return FakeResponse("node_exporter_build_info 1\n")
    if url.endswith(":8082/actuator/health"):
        return FakeResponse(json.dumps({"status": "UP"}))
    raise AssertionError(url)


class DistributedObservabilityVerificationTest(unittest.TestCase):
    def test_all_three_endpoints_pass(self):
        MODULE.verify("192.0.2.10", attempts=1, interval=0, opener=healthy_opener)

    def test_ipv6_is_bracketed_in_endpoint_urls(self):
        urls = []

        def opener(url, timeout):
            urls.append(url)
            return healthy_opener(url, timeout)

        MODULE.verify("2001:db8::10", attempts=1, interval=0, opener=opener)
        self.assertTrue(all("[2001:db8::10]" in url for url in urls))

    def test_missing_exporter_metric_fails(self):
        def opener(url, timeout):
            if url.endswith(":9308/metrics"):
                return FakeResponse("unrelated_metric 1\n")
            return healthy_opener(url, timeout)

        with self.assertRaisesRegex(MODULE.VerificationError, "kafka_brokers"):
            MODULE.verify("192.0.2.10", attempts=1, interval=0, opener=opener)

    def test_unhealthy_kafka_ui_is_retried(self):
        health_calls = 0
        sleeps = []

        def opener(url, timeout):
            nonlocal health_calls
            if url.endswith(":8082/actuator/health"):
                health_calls += 1
                status = "DOWN" if health_calls == 1 else "UP"
                return FakeResponse(json.dumps({"status": status}))
            return healthy_opener(url, timeout)

        MODULE.verify(
            "192.0.2.10", attempts=2, interval=0.25, opener=opener, sleeper=sleeps.append
        )
        self.assertEqual(health_calls, 2)
        self.assertEqual(sleeps, [0.25])

    def test_hostname_and_invalid_retry_values_are_rejected(self):
        with self.assertRaisesRegex(MODULE.VerificationError, "IPv4 or IPv6"):
            MODULE.verify("distributed.internal", attempts=1, interval=0, opener=healthy_opener)
        with self.assertRaisesRegex(MODULE.VerificationError, "attempts"):
            MODULE.verify("192.0.2.10", attempts=0, interval=0, opener=healthy_opener)


if __name__ == "__main__":
    unittest.main()
