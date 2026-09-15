import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

spec = importlib.util.spec_from_file_location('backend_pilot', Path(__file__).with_name('ci-rootless-backend-check.py'))
pilot = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pilot)


def archive_bytes(extra=None):
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode='w') as archive:
        for name in ['backend/gradlew', 'backend/build.gradle', 'backend/settings.gradle',
                     'backend/gradle/wrapper/gradle-wrapper.jar']:
            info = tarfile.TarInfo(name)
            info.size = 1
            archive.addfile(info, io.BytesIO(b'x'))
        if extra:
            archive.addfile(extra, io.BytesIO(b''))
    return buffer.getvalue()


class BackendPilotTests(unittest.TestCase):
    def test_plan_has_no_external_actions(self):
        with patch.object(pilot.sys, 'argv', ['pilot']), patch.object(pilot, 'run') as run, \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(pilot.main(), 0)
        run.assert_not_called()

    def test_run_requires_all_confirmations(self):
        with patch.object(pilot.sys, 'argv', ['pilot', 'run']), patch.object(pilot, 'run') as run, \
                contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            pilot.main()
        run.assert_not_called()

    def test_extract_only_snapshot_and_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            pilot.extract_snapshot(archive_bytes(), Path(directory))
            self.assertEqual((Path(directory) / 'backend/gradlew').read_bytes(), b'x')
            self.assertFalse((Path(directory) / '.git').exists())

    def test_archive_rejects_escape_links_secrets_local_state_and_duplicates(self):
        for name in ['../escape', '/backend/a', 'infra/config', 'backend/../escape',
                     'backend/evil\\path', 'backend/.env', 'backend/.env.local',
                     'backend/.git/config', 'backend/build/old.xml', 'backend/gradlew']:
            with self.subTest(name=name), self.assertRaises(ValueError):
                pilot.archive_entries(archive_bytes(tarfile.TarInfo(name)))
        for kind in [tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.CHRTYPE, tarfile.FIFOTYPE]:
            item = tarfile.TarInfo('backend/link')
            item.type = kind
            item.linkname = '/etc/passwd'
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                pilot.archive_entries(archive_bytes(item))

    def test_environment_does_not_copy_operating_secrets(self):
        with patch.dict(pilot.os.environ, {'DB_PASSWORD': 'secret', 'DOCKER_AUTH_CONFIG': 'secret',
                                          'TESTCONTAINERS_RYUK_DISABLED': 'true'}):
            env = pilot.environment(Path('/home/feelm-ci-test/backend-checks/run'), 1001,
                                    {'user': 1, 'net': 2, 'mnt': 3})
        self.assertNotIn('secret', json.dumps(env))
        self.assertEqual(env['DOCKER_HOST'], 'unix:///run/user/1001/docker.sock')
        self.assertEqual(env['TESTCONTAINERS_HOST_OVERRIDE'], '127.0.0.1')
        self.assertEqual(env['TESTCONTAINERS_RYUK_DISABLED'], 'false')
        self.assertEqual(env['TESTCONTAINERS_RYUK_CONTAINER_PRIVILEGED'], 'false')

    def test_child_compiles_and_keeps_all_test_tasks(self):
        compile(pilot.BACKEND_CHILD, 'backend-child', 'exec')
        self.assertIn("'clean', 'test', 'spotlessCheck'", pilot.BACKEND_CHILD)
        self.assertNotIn("'--tests'", pilot.BACKEND_CHILD)
        self.assertNotIn("'--privileged'", pilot.BACKEND_CHILD)

    def test_report_requires_all_container_suites(self):
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
            backend = Path(directory)
            reports = backend / 'build/test-results/test'
            reports.mkdir(parents=True)
            self.assertFalse(pilot.report_summary(backend))
            for name in pilot.REQUIRED_CLASSES:
                (reports / f'TEST-{name}.xml').write_text(
                    f'<testsuite name="한글 표시 이름" tests="1" failures="0" errors="0" skipped="0">'
                    f'<testcase classname="{name}" name="example"/></testsuite>', encoding='utf-8')
            self.assertTrue(pilot.report_summary(backend))
            (reports / 'TEST-AuthContractTest.xml').write_text(
                '<testsuite name="duplicate" tests="1" skipped="1">'
                '<testcase classname="com.feelm.backend.domain.auth.AuthContractTest"><skipped/></testcase>'
                '</testsuite>')
            self.assertFalse(pilot.report_summary(backend))

    def test_report_rejects_pretty_name_only_wrong_package_and_failed_cases(self):
        for variant in ('no-case', 'wrong-package', 'failure', 'error', 'skipped', 'negative'):
            with self.subTest(variant=variant), tempfile.TemporaryDirectory() as directory, \
                    contextlib.redirect_stdout(io.StringIO()):
                backend = Path(directory)
                reports = backend / 'build/test-results/test'
                reports.mkdir(parents=True)
                for name in pilot.REQUIRED_CLASSES:
                    classname = 'wrong.' + name.rsplit('.', 1)[-1] if variant == 'wrong-package' else name
                    child = f'<{variant}/>' if variant in ('failure', 'error', 'skipped') else ''
                    case = '' if variant == 'no-case' else f'<testcase classname="{classname}">{child}</testcase>'
                    count = -1 if variant == 'negative' else 1
                    (reports / f'TEST-{name}.xml').write_text(
                        f'<testsuite name="{name}" tests="{count}">{case}</testsuite>')
                self.assertFalse(pilot.report_summary(backend))

    def test_cleanup_waits_for_natural_disappearance_without_deleting(self):
        with patch.object(pilot.network, 'execute', side_effect=[
                SimpleNamespace(stdout='abc\n'), SimpleNamespace(stdout='')]) as execute, \
                patch.object(pilot.time, 'monotonic', side_effect=[0, 0, 2]), \
                patch.object(pilot.time, 'sleep') as sleep:
            self.assertEqual(pilot.wait_for_container_cleanup(['docker']), [])
        self.assertEqual(execute.call_count, 2)
        self.assertTrue(all(call.args == (['docker'], 'ps', '-aq') for call in execute.call_args_list))
        sleep.assert_called_once_with(2)

    def test_cleanup_timeout_keeps_remaining_ids(self):
        with patch.object(pilot.network, 'execute', return_value=SimpleNamespace(stdout='abc\n')), \
                patch.object(pilot.time, 'monotonic', side_effect=[0, 30]), \
                patch.object(pilot.time, 'sleep') as sleep:
            self.assertEqual(pilot.wait_for_container_cleanup(['docker']), ['abc'])
        sleep.assert_not_called()

    def test_report_action_does_not_run_tests_or_docker(self):
        with patch.object(pilot.sys, 'argv', ['pilot', 'report', '--run-dir', '/existing']), \
                patch.object(pilot, 'recheck_report', return_value=0) as report, \
                patch.object(pilot, 'run') as run, patch.object(pilot.network, 'execute') as execute:
            self.assertEqual(pilot.main(), 0)
        report.assert_called_once_with('/existing')
        run.assert_not_called()
        execute.assert_not_called()

    def test_saved_report_recheck_preserves_failure_and_is_read_only(self):
        for passed in (True, False):
            with self.subTest(passed=passed), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                saved = root / ('a' * 32)
                with patch.object(Path, 'resolve', side_effect=[root, saved]), \
                        patch.object(pilot, 'report_summary', return_value=passed) as summary, \
                        patch.object(pilot.network, 'execute') as execute, \
                        contextlib.redirect_stdout(io.StringIO()) as output:
                    self.assertEqual(pilot.recheck_report(str(saved)), 0 if passed else 2)
                summary.assert_called_once_with(saved / 'backend')
                execute.assert_not_called()
                self.assertIn('EXISTING_XML_ONLY', output.getvalue())

    def test_saved_report_rejects_outside_or_non_uuid_directory(self):
        root = Path('/saved')
        for saved in (Path('/elsewhere') / ('a' * 32), root / 'not-a-run'):
            with patch.object(Path, 'resolve', side_effect=[root, saved]), \
                    self.assertRaises(ValueError):
                pilot.recheck_report(str(saved))

    def test_resource_limits_reject_wrong_cgroup_or_unlimited_memory(self):
        with patch.object(Path, 'read_text', return_value='0::/system.slice/example.service\n'), \
                self.assertRaises(ValueError):
            pilot.require_resource_slice(1001)
        with patch.object(Path, 'read_text', side_effect=['0::/user.slice/user-1001.slice/app.slice/x\n', 'max']), \
                self.assertRaises(ValueError):
            pilot.require_resource_slice(1001)

    def test_resource_limits_accept_bounded_slice(self):
        with patch.object(Path, 'read_text', side_effect=[
                '0::/user.slice/user-1001.slice/app.slice/x\n', str(6 * 1024**3),
                str(5 * 1024**3), '2048', '200000 100000']):
            pilot.require_resource_slice(1001)


if __name__ == '__main__':
    unittest.main()
