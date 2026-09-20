import importlib.util
import json
import subprocess
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("verify-spark-history.py")
SPEC = importlib.util.spec_from_file_location("verify_spark_history", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class FakeResponse:
    def __init__(self, payload):
        self.body = json.dumps(payload).encode()

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self):
        return self.body


def hdfs_runner(_command, **_kwargs):
    return subprocess.CompletedProcess([], 0, stdout="1777\n", stderr="")


class SparkHistoryVerificationTest(unittest.TestCase):
    def test_event_log_directory_and_history_api_pass(self):
        urls = []

        def opener(url, timeout):
            urls.append((url, timeout))
            return FakeResponse([])

        MODULE.verify(
            "192.0.2.10", attempts=1, interval=0, opener=opener, runner=hdfs_runner
        )
        self.assertEqual(urls, [("http://192.0.2.10:18080/api/v1/applications", 5)])

    def test_event_log_permission_must_be_1777(self):
        def runner(_command, **_kwargs):
            return subprocess.CompletedProcess([], 0, stdout="755\n", stderr="")

        with self.assertRaisesRegex(MODULE.VerificationError, "permission"):
            MODULE.verify(
                "192.0.2.10", attempts=1, interval=0, opener=lambda *_a: None, runner=runner
            )

    def test_missing_event_log_directory_fails_before_http(self):
        def runner(_command, **_kwargs):
            return subprocess.CompletedProcess([], 1, stdout="", stderr="missing")

        with self.assertRaisesRegex(MODULE.VerificationError, "unavailable"):
            MODULE.verify(
                "192.0.2.10", attempts=1, interval=0, opener=lambda *_a: None, runner=runner
            )

    def test_history_api_requires_a_list_and_retries(self):
        calls = 0

        def opener(_url, timeout):
            nonlocal calls
            self.assertEqual(timeout, 5)
            calls += 1
            return FakeResponse({} if calls == 1 else [])

        MODULE.verify(
            "192.0.2.10",
            attempts=2,
            interval=0,
            opener=opener,
            runner=hdfs_runner,
            sleeper=lambda _seconds: None,
        )
        self.assertEqual(calls, 2)

    def test_invalid_host_is_rejected(self):
        with self.assertRaisesRegex(MODULE.VerificationError, "IPv4 or IPv6"):
            MODULE.verify_history_api("not-an-address", lambda *_args: FakeResponse([]))


if __name__ == "__main__":
    unittest.main()
