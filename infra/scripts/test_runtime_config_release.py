import importlib.util
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("runtime-config-release.py")
SPEC = importlib.util.spec_from_file_location("runtime_config_release", SCRIPT)
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class RuntimeConfigReleaseTests(unittest.TestCase):
    def test_state_is_scoped_by_component_and_node(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            store = release.ReleaseStore(root, Path.cwd())
            revision = "a" * 40
            (root / "releases" / revision).mkdir(parents=True)
            store.write_current("hdfs", "service", revision)
            self.assertEqual(store.read_current("hdfs", "service"), revision)
            with self.assertRaisesRegex(release.ReleaseError, "not initialized"):
                store.read_current("hdfs", "distributed")

    def test_existing_release_requires_matching_marker(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            store = release.ReleaseStore(root, Path.cwd())
            revision = "b" * 40
            candidate = root / "releases" / revision
            candidate.mkdir(parents=True)
            (candidate / ".feelm-release-revision").write_text(revision + "\n")
            self.assertEqual(store.install(revision), candidate)
            (candidate / ".feelm-release-revision").write_text("c" * 40 + "\n")
            with self.assertRaisesRegex(release.ReleaseError, "invalid content"):
                store.install(revision)

    def test_invalid_revision_is_rejected(self):
        with self.assertRaisesRegex(release.ReleaseError, "full lowercase"):
            release.validate_sha("ABC123")

    def test_unsupported_state_scope_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            store = release.ReleaseStore(Path(directory), Path.cwd())
            with self.assertRaisesRegex(release.ReleaseError, "unsupported"):
                store.state_file("spark", "distributed")


if __name__ == "__main__":
    unittest.main()
