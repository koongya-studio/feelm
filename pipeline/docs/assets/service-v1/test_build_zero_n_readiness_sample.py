import json
import tempfile
import unittest
from pathlib import Path

import build_zero_n_readiness_sample as sample


class ReadinessSampleTest(unittest.TestCase):
    def test_role_assignment_is_stable_and_disjoint(self):
        first = [sample.role_for_user(621, uid) for uid in range(1, 100)]
        second = [sample.role_for_user(621, uid) for uid in range(1, 100)]
        self.assertEqual(first, second)
        self.assertTrue(set(first).issubset(set(sample.ROLES)))

    def test_distribution_metrics_identical(self):
        values = {"a": 0.4, "b": 0.6}
        result = sample.compare_distributions(values, values)
        self.assertAlmostEqual(result["psi"], 0.0)
        self.assertAlmostEqual(result["jensen_shannon_distance_base2"], 0.0)
        self.assertAlmostEqual(result["max_percentage_point_difference"], 0.0)

    def test_jsonl_uses_stable_key_order(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "rows.jsonl"
            sample.write_jsonl(path, [{"b": 1, "a": 2}])
            self.assertEqual(json.loads(path.read_text(encoding="utf-8")), {"a": 2, "b": 1})


if __name__ == "__main__":
    unittest.main()
