import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location("check", Path(__file__).with_name("kafka-hdfs-check.py"))
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


class VerificationTests(unittest.TestCase):
    def test_valid_base(self):
        self.assertEqual(check.validate_base("hdfs://nn:8020/verification/S15P21E106-137/run/"),
                         "hdfs://nn:8020/verification/S15P21E106-137/run")

    def test_unsafe_paths(self):
        for value in ("file:///tmp/run", "hdfs://nn/", "hdfs://nn/verification/S15P21E106-137/",
                      "hdfs://nn/verification/S15P21E106-137/../other",
                      "hdfs://nn/verification/S15P21E106-137/%2e%2e",
                      "hdfs://user:secret@nn/verification/S15P21E106-137/run",
                      "hdfs://nn/verification/S15P21E106-137/run?q=1"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                check.validate_base(value)

    def load(self, events):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "expected.json"
            path.write_text(json.dumps(events), encoding="utf-8")
            return check.load_expected(path)

    def test_expected(self):
        self.assertEqual(self.load([{"event_id": "a", "value": 1}]), {"a": 1})

    def test_bad_expected(self):
        for events in ([], {}, [{"event_id": "a", "value": True}],
                       [{"event_id": "a", "value": 1}] * 2,
                       [{"event_id": "", "value": 1}],
                       [{"event_id": "a", "value": 1, "extra": 2}]):
            with self.subTest(events=events), self.assertRaises(ValueError):
                self.load(events)

    def rows(self):
        return [dict(topic="topic", partition=0, offset=0,
                     payload='{"event_id":"a","value":1}'),
                dict(topic="topic", partition=1, offset=0,
                     payload='{"event_id":"b","value":2}')]

    def test_rows_order_independent(self):
        records, offsets = check.verify_rows(self.rows()[::-1], {"a": 1, "b": 2}, "topic")
        self.assertEqual(len(records), 2)
        self.assertEqual(len(offsets), 2)

    def test_missing_rows(self):
        with self.assertRaises(ValueError):
            check.verify_rows(self.rows()[:1], {"a": 1, "b": 2}, "topic")

    def test_wrong_contents(self):
        with self.assertRaises(ValueError):
            check.verify_rows(self.rows(), {"a": 1, "b": 3}, "topic")

    def test_duplicate_id(self):
        rows = self.rows()
        rows[1]["payload"] = rows[0]["payload"]
        with self.assertRaises(ValueError):
            check.verify_rows(rows, {"a": 1, "b": 2}, "topic")

    def test_duplicate_offset(self):
        rows = self.rows()
        rows[1]["partition"] = 0
        with self.assertRaises(ValueError):
            check.verify_rows(rows, {"a": 1, "b": 2}, "topic")

    def test_wrong_topic(self):
        with self.assertRaises(ValueError):
            check.verify_rows(self.rows(), {"a": 1, "b": 2}, "another")

    def test_invalid_payload(self):
        rows = self.rows()
        rows[0]["payload"] = "not JSON"
        with self.assertRaises(ValueError):
            check.verify_rows(rows, {"a": 1, "b": 2}, "topic")

    def test_init_existing_rejected(self):
        with self.assertRaises(ValueError):
            check.check_paths(lambda path: True, "base", "init")

    def test_init_new_allowed(self):
        check.check_paths(lambda path: False, "base", "init")

    def test_resume_missing_metadata_rejected(self):
        with self.assertRaises(ValueError):
            check.check_paths(lambda path: not path.endswith("metadata"), "base", "resume")

    def test_resume_existing_allowed(self):
        check.check_paths(lambda path: True, "base", "resume")


if __name__ == "__main__":
    unittest.main()
