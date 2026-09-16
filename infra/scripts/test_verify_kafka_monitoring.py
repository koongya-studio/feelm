import json
import unittest
from urllib.parse import parse_qs, urlsplit

from infra.scripts.verify_kafka_monitoring import VerificationError, verify


class FakeResponse:
    def __init__(self, value):
        self.body = json.dumps(
            {
                "status": "success",
                "data": {
                    "resultType": "vector",
                    "result": [] if value is None else [{"metric": {}, "value": [1, str(value)]}],
                },
            }
        ).encode()

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self):
        return self.body


def opener_with(*, target=1, brokers=1, lag=0):
    def open_url(url, timeout):
        assert timeout == 5
        query = parse_qs(urlsplit(url).query)["query"][0]
        if query.startswith("up{"):
            return FakeResponse(target)
        if query.startswith("kafka_brokers{"):
            return FakeResponse(brokers)
        if query.startswith("sum(kafka_consumergroup_lag{"):
            return FakeResponse(lag)
        raise AssertionError(query)

    return open_url


class KafkaMonitoringVerificationTest(unittest.TestCase):
    def test_healthy_target_broker_and_drained_consumer_pass(self):
        result = verify(
            "http://127.0.0.1:9090",
            "feelm-foldin-consumer-v1",
            max_lag=0,
            opener=opener_with(),
        )
        self.assertEqual({"target_up": 1, "brokers": 1, "lag": 0}, result)

    def test_exporter_down_fails_before_broker_and_lag_checks(self):
        with self.assertRaisesRegex(VerificationError, "DOWN"):
            verify(
                "http://127.0.0.1:9090",
                "feelm-foldin-consumer-v1",
                opener=opener_with(target=0),
            )

    def test_induced_lag_can_be_required(self):
        result = verify(
            "http://127.0.0.1:9090",
            "feelm-foldin-consumer-v1",
            min_lag=10,
            opener=opener_with(lag=12),
        )
        self.assertEqual(12, result["lag"])

    def test_missing_or_insufficient_lag_fails(self):
        with self.assertRaisesRegex(VerificationError, "최소 기대값"):
            verify(
                "http://127.0.0.1:9090",
                "feelm-foldin-consumer-v1",
                min_lag=10,
                opener=opener_with(lag=3),
            )

        with self.assertRaisesRegex(VerificationError, "정확히 하나"):
            verify(
                "http://127.0.0.1:9090",
                "feelm-foldin-consumer-v1",
                opener=opener_with(lag=None),
            )


if __name__ == "__main__":
    unittest.main()
