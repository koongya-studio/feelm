import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("spark-code-state.py")
SPEC = importlib.util.spec_from_file_location("spark_code_state", SCRIPT)
state = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
sys.modules[SPEC.name] = state
SPEC.loader.exec_module(state)


class SparkCodeStateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.store = state.StateStore(Path(self.temporary.name), "distributed")
        self.previous = state.DeploymentState(
            "a" * 40, "repo/spark@sha256:" + "1" * 64, "a" * 40
        )
        self.candidate = state.DeploymentState(
            "b" * 40, "repo/spark@sha256:" + "2" * 64, "b" * 40
        )

    def test_manifest_is_replaced_as_one_atomic_file(self):
        self.store.initialize(self.previous)
        self.store.begin(self.candidate)
        with patch.object(Path, "replace", side_effect=OSError("interrupted")):
            with self.assertRaises(OSError):
                self.store.promote(self.candidate)
        self.assertEqual(self.store.read_manifest(), self.previous)
        self.assertEqual(self.store.read_pending().candidate, self.candidate)

    def test_failed_transaction_restores_previous_manifest_and_finalizes(self):
        self.store.initialize(self.previous)
        self.store.begin(self.candidate)
        self.store.promote(self.candidate)
        self.store.restore_state(self.candidate)
        self.assertEqual(self.store.resolve(self.candidate, "failed"), "rolled_back")
        self.assertEqual(self.store.read_manifest(), self.previous)
        self.assertFalse(self.store.pending.exists())

    def test_success_retry_is_idempotent_after_pending_is_removed(self):
        self.store.initialize(self.previous)
        self.store.begin(self.candidate)
        self.store.promote(self.candidate)
        self.assertEqual(self.store.resolve(self.candidate, "success"), "success")
        self.assertEqual(self.store.resolve(self.candidate, "success"), "success")

    def test_failed_resolution_without_local_pending_is_a_noop(self):
        self.store.initialize(self.candidate)
        self.assertEqual(self.store.resolve(self.candidate, "failed"), "rolled_back")

    def test_mismatched_candidate_cannot_promote_or_resolve(self):
        other = state.DeploymentState(
            "c" * 40, "repo/spark@sha256:" + "3" * 64, "c" * 40
        )
        self.store.initialize(self.previous)
        self.store.begin(self.candidate)
        with self.assertRaisesRegex(state.StateError, "does not match"):
            self.store.promote(other)
        with self.assertRaisesRegex(state.StateError, "does not match"):
            self.store.resolve(other, "failed")

    def test_manifest_rejects_mixed_or_invalid_fields(self):
        with self.assertRaisesRegex(state.StateError, "source revision"):
            state.DeploymentState.parse({
                "revision": "a" * 40,
                "image": "repo/spark@sha256:" + "1" * 64,
                "source_revision": "legacy",
            })


if __name__ == "__main__":
    unittest.main()
