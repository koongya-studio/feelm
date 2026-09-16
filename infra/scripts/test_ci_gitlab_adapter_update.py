import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
from contextlib import nullcontext
from pathlib import Path
from unittest.mock import Mock, patch

HERE = Path(__file__).resolve().parent
UPDATER = HERE / "ci-gitlab-adapter-update.py"


def load_updater():
    if not UPDATER.is_file():
        raise AssertionError("adapter-only updater is missing")
    spec = importlib.util.spec_from_file_location("gitlab_adapter_update", UPDATER)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class AdapterUpdateTests(unittest.TestCase):
    def test_default_is_read_only_plan(self):
        updater = load_updater()
        with patch("sys.argv", ["updater"]), patch.object(updater, "update") as update:
            self.assertEqual(updater.main(), 0)
            update.assert_not_called()

    def test_upload_manifest_covers_exact_staged_scripts_and_detects_changes(self):
        updater = load_updater()
        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            scripts = stage / "scripts"
            scripts.mkdir()
            script = scripts / "ci-gitlab-job-adapter.py"
            script.write_bytes(b"reviewed\n")
            manifest = {
                "scripts/ci-gitlab-job-adapter.py": hashlib.sha256(script.read_bytes()).hexdigest(),
                "backend.tar": "0" * 64,
            }
            (stage / "upload-manifest.json").write_text(json.dumps(manifest))

            with patch.object(updater, "trusted_tree"), patch.object(updater, "trusted_file"):
                self.assertEqual(updater.verify_upload(stage), scripts)
                script.write_bytes(b"changed\n")
                with self.assertRaises(ValueError):
                    updater.verify_upload(stage)

    def test_update_is_atomic_preserves_state_and_rolls_back(self):
        updater = load_updater()
        source = UPDATER.read_text()

        self.assertEqual(updater.TARGET.as_posix(), "/usr/local/lib/feelm-ci222/gitlab")
        self.assertEqual(updater.CONFIG.as_posix(), "/etc/feelm-ci222/gitlab-adapter.json")
        self.assertEqual(updater.OPERATORS, updater.TARGET / "cache-operators.json")
        self.assertIn("os.replace(TARGET, backup)", source)
        self.assertIn("os.replace(replacement, TARGET)", source)
        self.assertIn("os.replace(backup, TARGET)", source)
        self.assertIn("systemctl', 'stop', SERVICE", source)
        self.assertIn("systemctl', 'start', SERVICE", source)
        self.assertIn("with OPERATORS.open('rb')", source)
        self.assertIn("'ci352-owner-*.service'", source)
        self.assertIn("'ci486-owner-*.service'", source)
        self.assertIn("'ci490-infra-*.service'", source)
        self.assertNotIn("rmtree(", source)
        self.assertNotIn("os.replace(CONFIG", source)
        self.assertNotIn("os.replace(SUDOERS", source)

    def test_update_holds_dependency_cache_lock_before_preflight(self):
        updater = load_updater()
        with patch.object(updater, "exclusive_lock", create=True) as exclusive, patch.object(
            updater, "preflight", side_effect=RuntimeError("stop after lock check")
        ):
            exclusive.return_value = nullcontext()
            with self.assertRaisesRegex(RuntimeError, "stop after lock check"):
                updater.update("/var/tmp/ci222-server-test")

        exclusive.assert_called_once_with()

    def test_adapter_smoke_requires_expected_usage_exit(self):
        updater = load_updater()
        smoke = getattr(updater, "adapter_smoke", None)
        self.assertIsNotNone(smoke)
        adapter = Path("/stage/scripts/ci-gitlab-job-adapter.py")
        expected = Mock(returncode=64, stdout="", stderr="USAGE: adapter run\n")
        rejected = Mock(returncode=1, stdout="", stderr="ImportError\n")

        with patch.object(updater.subprocess, "run", return_value=expected):
            smoke(adapter)
        with patch.object(updater.subprocess, "run", return_value=rejected):
            with self.assertRaises(ValueError):
                smoke(adapter)

    def test_stop_failure_restores_original_active_service(self):
        updater = load_updater()
        replace_install = getattr(updater, "replace_install", None)
        self.assertIsNotNone(replace_install)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "gitlab"
            replacement = root / "replacement"
            target.mkdir()
            replacement.mkdir()
            (target / "marker").write_text("old")
            (replacement / "marker").write_text("new")

            with patch.object(updater, "TARGET", target), patch.object(
                updater, "service_active", return_value=True, create=True
            ), patch.object(
                updater, "stop_service", side_effect=TimeoutError("partial stop"), create=True
            ), patch.object(updater, "start_service", create=True) as start:
                with self.assertRaisesRegex(TimeoutError, "partial stop"):
                    replace_install(replacement)

            self.assertEqual((target / "marker").read_text(), "old")
            start.assert_called_once_with(check=False)

    def test_installed_adapter_smoke_failure_rolls_back(self):
        updater = load_updater()
        replace_install = getattr(updater, "replace_install", None)
        self.assertIsNotNone(replace_install)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "gitlab"
            replacement = root / "replacement"
            target.mkdir()
            replacement.mkdir()
            (target / "marker").write_text("old")
            (replacement / "marker").write_text("new")
            (replacement / "ci-gitlab-job-adapter.py").write_text("new adapter")

            with patch.object(updater, "TARGET", target), patch.object(
                updater, "service_active", side_effect=[True, False], create=True
            ), patch.object(updater, "active_units", return_value=""), patch.object(
                updater, "stop_service", create=True
            ), patch.object(updater, "start_service", create=True) as start, patch.object(
                updater,
                "adapter_smoke",
                side_effect=ValueError("adapter import failed"),
                create=True,
            ):
                with self.assertRaisesRegex(ValueError, "adapter import failed"):
                    replace_install(replacement)

            self.assertEqual((target / "marker").read_text(), "old")
            start.assert_called_once_with(check=False)

    def test_restart_failure_quiesces_runner_before_install_rollback(self):
        updater = load_updater()
        events = []
        real_replace = updater.os.replace
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "gitlab"
            replacement = root / "replacement"
            target.mkdir()
            replacement.mkdir()
            (target / "marker").write_text("old")
            (replacement / "marker").write_text("new")
            (replacement / "ci-gitlab-job-adapter.py").write_text("new adapter")

            def record_stop():
                events.append("stop")

            def record_start(check=True):
                events.append("start-checked" if check else "start-recovery")
                if check:
                    raise TimeoutError("start status unknown")

            def record_replace(source, destination):
                events.append("replace")
                real_replace(source, destination)

            with patch.object(updater, "TARGET", target), patch.object(
                updater, "service_active", side_effect=[True, False, False], create=True
            ), patch.object(updater, "active_units", return_value=""), patch.object(
                updater, "stop_service", side_effect=record_stop, create=True
            ), patch.object(
                updater, "start_service", side_effect=record_start, create=True
            ), patch.object(updater, "adapter_smoke", create=True), patch.object(
                updater.os, "replace", side_effect=record_replace
            ):
                with self.assertRaisesRegex(TimeoutError, "start status unknown"):
                    updater.replace_install(replacement)

            restart = events.index("start-checked")
            rollback = events.index("replace", restart)
            self.assertIn("stop", events[restart + 1 : rollback])
            self.assertEqual((target / "marker").read_text(), "old")

    def test_recovery_start_requires_success_and_active_service(self):
        updater = load_updater()
        cases = [
            (Mock(returncode=1), True, "start command failed"),
            (Mock(returncode=0), False, "did not become active"),
        ]
        for result, active, expected in cases:
            with self.subTest(expected=expected), patch.object(
                updater.subprocess, "run", return_value=result
            ), patch.object(updater, "service_active", return_value=active):
                with self.assertRaisesRegex(RuntimeError, expected):
                    updater.start_service(check=False)


if __name__ == "__main__":
    unittest.main()
