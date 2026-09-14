from contextlib import redirect_stdout
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent


def load(name):
    spec = importlib.util.spec_from_file_location(name, HERE / (name + '.py'))
    result = importlib.util.module_from_spec(spec); sys.modules[name] = result
    spec.loader.exec_module(result)
    return result


safety = load('ci-job-safety')
bounded = load('ci-bounded-output')


class OutputTests(unittest.TestCase):
    def test_normal_output_and_exit(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'log'
            child = bounded.CappedProcess([sys.executable, '-c', 'print("ok")'], path, 128)
            self.assertEqual(child.wait(10), 0)
            child.reap(10)
            self.assertEqual(path.read_text().strip(), 'ok')

    def test_output_limit_and_exact_cap(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'log'
            child = bounded.CappedProcess([sys.executable, '-c', 'print("x" * 10000)'], path, 128)
            with self.assertRaises(RuntimeError): child.wait(10)
            child.reap(10)
            self.assertEqual(path.stat().st_size, 128)
            self.assertTrue(child.overflow.is_set())

    def test_refuses_existing_output_and_invalid_limit(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'log'; path.write_text('keep')
            with self.assertRaises(FileExistsError): bounded.CappedProcess([], path)
            with self.assertRaises(ValueError): bounded.CappedProcess([], path, 0)
            self.assertEqual(path.read_text(), 'keep')

    @unittest.skipUnless(sys.platform.startswith('linux'), 'Linux descriptor check')
    def test_unrelated_inheritable_fd_not_forwarded(self):
        with tempfile.TemporaryDirectory() as directory:
            secret = Path(directory) / 'canary'; secret.write_text('not for job')
            fd = os.open(secret, os.O_RDONLY); os.set_inheritable(fd, True)
            try:
                code = ('import os; '
                        'print(any(os.path.realpath("/proc/self/fd/"+n)=='+repr(str(secret))+
                        ' for n in os.listdir("/proc/self/fd")))')
                output = Path(directory) / 'log'
                child = bounded.CappedProcess([sys.executable, '-c', code], output)
                self.assertEqual(child.wait(10), 0); child.reap(10)
                self.assertEqual(output.read_text().strip(), 'False')
            finally: os.close(fd)


class SafetyTests(unittest.TestCase):
    def test_default_plan_no_mutation(self):
        with patch('sys.argv', ['safety']), patch.object(safety, 'start') as start:
            self.assertEqual(safety.main(), 0); start.assert_not_called()

    def test_missing_cleanup_proof_blocks_admission(self):
        with tempfile.TemporaryDirectory() as directory:
            lease = Path(directory) / 'lease.json'; lease.write_text('{}')
            with patch.object(Path, 'glob', return_value=iter([lease])), \
                 patch.object(safety, 'validate_attempt', return_value={'id': 'test'}):
                with self.assertRaisesRegex(RuntimeError, 'Unresolved'):
                    safety.inventory()

    def test_failed_cleanup_proof_blocks_admission(self):
        with tempfile.TemporaryDirectory() as directory:
            lease = Path(directory) / 'lease.json'; lease.write_text('{}')
            (lease.parent / 'cleanup.json').write_text('{"id":"test","clean":false}')
            with patch.object(Path, 'glob', return_value=iter([lease])), \
                 patch.object(safety, 'validate_attempt', return_value={'id': 'test'}):
                with self.assertRaisesRegex(RuntimeError, 'not proven'):
                    safety.inventory()

    def test_lease_rejects_arbitrary_units_before_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            attempt = Path(directory)
            # Test policy parsing separately from Linux root-owned path validation.
            token = '0123456789ab'
            lease = {'id': token, 'owner': 'docker.service', 'build': 'bad', 'execution': 'bad',
                     'bytes': safety.DISK_BYTES, 'mode': 'probe', 'prepared': '/prepared',
                     'source_sha': None}
            (attempt / 'lease.json').write_text(json.dumps(lease))
            info = type('Info', (), {'st_uid': 0, 'st_mode': 0o100600, 'st_size': 100})()
            with patch.object(safety.pilot, 'check_base'), patch.object(Path, 'stat', return_value=info), \
                 patch.object(Path, 'lstat', return_value=info), patch.object(Path, 'is_symlink', return_value=False), \
                 patch.object(safety.re, 'fullmatch', side_effect=lambda pattern, text: True):
                with self.assertRaisesRegex(ValueError, 'Wrong owner'):
                    safety.validate_attempt(attempt)

    def test_legacy_fixed_source_lease_remains_readable(self):
        token = '0123456789ab'
        lease = {'id': token, 'owner': 'ci222-owner-' + token + '.service',
                 'build': 'ci222-server-' + token + '.service',
                 'execution': 'ci222-server-' + safety.hashlib.sha256(token.encode()).hexdigest()[:12] + '.service',
                 'bytes': safety.DISK_BYTES, 'mode': 'backend', 'prepared': '/prepared'}
        with tempfile.TemporaryDirectory() as directory:
            attempt = Path(directory); (attempt / 'lease.json').write_text(json.dumps(lease))
            info = type('Info', (), {'st_uid': 0, 'st_mode': 0o100600, 'st_size': 256})()
            with patch.object(safety.pilot, 'check_base'), patch.object(Path, 'stat', return_value=info), \
                 patch.object(Path, 'lstat', return_value=info), patch.object(Path, 'is_symlink', return_value=False), \
                 patch.object(safety.re, 'fullmatch', side_effect=lambda pattern, text: True):
                self.assertIsNone(safety.validate_attempt(attempt)['source_sha'])

    def test_owner_uses_pid1_cleanup_and_hard_timeout(self):
        args = safety.owner_command(Path('/stage'), Path('/attempt'), {'owner': 'ci222-owner-0123456789ab.service'})
        self.assertIn('--property=RuntimeMaxSec=2100', args)
        self.assertIn('--property=KillMode=control-group', args)
        self.assertIn('--collect', args)
        self.assertNotIn('--pipe', args)
        self.assertTrue(any('ExecStopPost=' in x and ' cleanup --attempt=' in x for x in args))

    def test_dynamic_source_is_validated_before_admission(self):
        with patch.object(safety.pilot, 'check_base'), \
             patch.object(safety.pilot.job_io, 'source_plan', side_effect=ValueError('bad')) as validate:
            with self.assertRaisesRegex(ValueError, 'bad'):
                safety.start(Path('/stage'), Path('/prepared'), 'backend', b'bad', '0' * 64)
            validate.assert_called_once_with(b'bad', '0' * 64)

    def test_backend_timing_publishes_only_validated_summary(self):
        timing = {
            'schema_version': 1, 'gradle_seconds': 12.345,
            'slowest_tasks': [{'path': ':test', 'seconds': 8.5}],
            'test_case_seconds': 1.25, 'test_count': 1,
            'slowest_classes': [
                {'class_name': 'com.example.Tests', 'seconds': 1.25, 'tests': 1}
            ],
            'informational_only': True,
        }
        with tempfile.TemporaryDirectory() as directory:
            attempt = Path(directory)
            evidence = attempt / 'evidence'; evidence.mkdir()
            manifest = evidence / 'manifest.json'
            manifest.write_text(json.dumps({'timing': timing}))
            trusted = type('Info', (), {'st_uid': 0, 'st_mode': 0o100600,
                                         'st_nlink': 1, 'st_size': manifest.stat().st_size})()
            output = io.StringIO()
            with patch.object(Path, 'lstat', return_value=trusted), redirect_stdout(output):
                safety.publish_backend_timing(attempt, 'backend', required=True)
            self.assertTrue(output.getvalue().startswith('BACKEND_TEST_TIMING='))

    def test_backend_timing_rejects_unvalidated_content_on_success(self):
        with tempfile.TemporaryDirectory() as directory:
            attempt = Path(directory)
            evidence = attempt / 'evidence'; evidence.mkdir()
            (evidence / 'manifest.json').write_text(json.dumps({'timing': {'unsafe': 'value'}}))
            with self.assertRaisesRegex(RuntimeError, 'timing evidence missing'):
                safety.publish_backend_timing(attempt, 'backend', required=True)

    def test_child_is_bound_to_owner_without_host_settings(self):
        args = safety.pilot.service_command('ci222-server-0123456789ab.service', Path('/stage'),
                  'internal-launch', Path('/attempt'), 'probe', Path('/prepared'), 'ci222-owner-0123456789ab.service')
        self.assertIn('--property=BindsTo=ci222-owner-0123456789ab.service', args)
        self.assertIn('--property=After=ci222-owner-0123456789ab.service', args)
        with self.assertRaises(ValueError):
            safety.pilot.service_command('ci222-server-0123456789ab.service', Path('/stage'),
                  'internal-launch', Path('/attempt'), 'probe', Path('/prepared'), 'docker.service')

    def test_cleanup_failure_does_not_write_success(self):
        lease = {'build': 'build', 'execution': 'execution'}
        with patch.object(safety, 'validate_attempt', return_value=lease), \
             patch.object(safety.pilot, 'stop_unit', side_effect=[RuntimeError('fixture'), None]) as stop, \
             patch.object(safety, 'atomic_json') as write:
            with self.assertRaises(RuntimeError): safety.cleanup(Path('/attempt'))
            self.assertEqual(stop.call_count, 2); write.assert_not_called()

    def test_probe_cleanup_and_collection_are_separate(self):
        with tempfile.TemporaryDirectory() as directory:
            attempt = Path(directory)
            (attempt / 'execution.log').write_bytes(b'PRIVILEGED_HOST_NAMESPACE_PROBE_PASSED')
            driver = safety.pilot.BackendJobDriver(Path('/base'), Path('/prepared'), attempt, 'probe')
            with patch.object(safety.pilot, 'verify_unit_stopped'):
                self.assertIsNone(driver.verify_stopped(60))
                self.assertTrue(driver.cleanup_confirmed)
                self.assertTrue(driver.collect(0, 60)['passed'])
                self.assertFalse(driver.collect(1, 60)['passed'])

    def test_disk_and_logs_have_independent_limits(self):
        self.assertEqual(safety.DISK_BYTES, 8 * 1024**3)
        self.assertEqual(bounded.LIMIT, 16 * 1024**2)
        source = (HERE / 'ci-job-safety.py').read_text()
        self.assertIn('os.O_CREAT | os.O_EXCL', source)
        self.assertIn("['umount', str(root)]", source)
        self.assertNotIn("'--detach-all'", source)
        self.assertNotIn("['umount', '-l'", source)
        self.assertIn("image.unlink()", source)
        self.assertIn('Unresolved previous safety attempt', source)


if __name__ == '__main__': unittest.main()
