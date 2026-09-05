import hashlib
import importlib.util
import io
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent


def load(name):
    spec = importlib.util.spec_from_file_location(name, HERE / (name + '.py'))
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module


pilot = load('ci-server-manager-pilot')
stage = load('ci-server-pilot-stage')


class ServerPilotTests(unittest.TestCase):
    def test_default_plan_has_no_side_effect(self):
        with patch('sys.argv', ['pilot', 'plan']), patch.object(pilot, 'check_base') as check:
            self.assertEqual(pilot.main(), 0)
            check.assert_not_called()

    def test_confirmation_required(self):
        with patch('sys.argv', ['pilot', 'backend']), patch.object(pilot, 'execute') as execute:
            pilot.main(); execute.assert_not_called()

    def test_operating_directory_rejected(self):
        with self.assertRaises(ValueError): pilot.check_base(Path('/home/ubuntu/S15P21E106'))

    def test_unrelated_cgroup_cleanup_refused(self):
        for group in ('/', '/system.slice', '/system.slice/gitlab-runner.service',
                      '/user.slice/user-1001.slice', '/system.slice/ci222-server-123.service/../other'):
            with self.assertRaises(ValueError): pilot.stop_unit(group)

    def test_launcher_is_bounded_and_preparation_is_separate(self):
        args = pilot.service_command('ci222-server-0123456789ab.service', Path('/stage'),
                                     'internal-launch', Path('/attempt'), 'probe', Path('/prepared'))
        for flag in ('--property=DelegateSubgroup=controller', '--property=MemoryMax=6G',
                     '--property=CPUQuota=200%', '--property=RuntimeMaxSec=2400', '--property=KillMode=control-group'):
            self.assertIn(flag, args)
        source = (HERE / 'ci-server-manager-pilot.py').read_text()
        self.assertIn('os.execve(nspawn, args, env)', source)
        self.assertIn("if (parent / 'cgroup.procs').read_text().strip():", source)

    def test_namespace_options_and_no_host_bind(self):
        with patch.object(pilot.os, 'readlink', return_value='namespace:[123]'):
            command = pilot.command(Path('/nspawn'), Path('/rootfs'), Path('/canary'), Path('/socket'))
        for value in ('--private-users=pick', '--private-network', '--keep-unit', '--settings=no', '--system-call-filter=keyctl'):
            self.assertIn(value, command)
        self.assertFalse(any(x.startswith('--bind') for x in command))
        self.assertNotIn('SYSTEMD_SECCOMP=0', ' '.join(command))

    def test_copy_allows_empty_verified_cache_file(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'source'; source.write_bytes(b'')
            target = Path(directory) / 'target'
            pilot.copy_checked(source, target, hashlib.sha256(b'').hexdigest(), 0)
            self.assertEqual(target.read_bytes(), b'')

    def test_copy_rejects_wrong_hash_and_preserves_target(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'source'; source.write_bytes(b'data')
            target = Path(directory) / 'target'; target.write_bytes(b'existing')
            with self.assertRaises(ValueError): pilot.copy_checked(source, target, '0' * 64, 4)
            self.assertEqual(target.read_bytes(), b'existing')

    def test_upload_only_exact_paths_and_source(self):
        with patch.object(stage.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, b'wrong')):
            with self.assertRaises(ValueError): stage.archive_bytes()
        self.assertNotIn('.gitlab-ci.yml', stage.FILES)
        self.assertNotIn('infra/.env', stage.FILES)

    def test_upload_bootstrap_is_exclusive(self):
        code = stage.remote_bootstrap({'scripts/a.py': '0' * 64})
        self.assertIn("tempfile.mkdtemp(prefix='ci222-server-', dir='/var/tmp')", code)
        self.assertIn("target.open('xb')", code)
        self.assertIn("seen != {*expected, 'upload-manifest.json'}", code)

    def test_staged_script_no_installs_or_operating_docker(self):
        source = (HERE / 'ci-server-manager-pilot.py').read_text()
        self.assertNotIn("'apt-get', 'install'", source)
        self.assertNotIn('/var/run/docker.sock', source)
        self.assertIn("'apt-get', 'download'", source)
        self.assertIn("'RuntimeMaxUSec') != '40min'", source)
        self.assertIn("lock.bind('\\0feelm-ci222-server-manager-pilot')", source)

    def test_server_privileged_probe_requires_actual_execution(self):
        script = pilot.server_probe_script()
        self.assertIn('DEFAULT_PRIVILEGED_WRITABLE_SYSFS_REJECTED', script)
        self.assertIn('--mount type=bind,src=/run/ci222-sysfs,dst=/sys,readonly', script)
        self.assertIn('SYSFS_REMOUNT_WRITABLE_UNEXPECTED; exit 3', script)
        for marker in ('HOST_PID_NAMESPACE', 'HOST_NET_NAMESPACE', 'HOST_CGROUP_NAMESPACE',
                       'HOST_FILE_SOCKET_BIND_REJECTED', 'PRIVILEGED_HOST_NAMESPACE_PROBE_PASSED'):
            self.assertIn(marker, script)
        self.assertIn('test -z "$(docker ps -aq)"', script)

    def test_server_probe_fails_if_shared_fixture_changes(self):
        with patch.object(pilot.manager, 'SCRIPT', 'changed fixture'):
            with self.assertRaises(ValueError): pilot.server_probe_script()

    def test_backend_collection_requires_trusted_cleanup(self):
        driver = pilot.BackendJobDriver(Path('/base'), Path('/prepared'), Path('/attempt'))
        with patch.object(pilot.job_io, 'collect_reports') as collect:
            with self.assertRaises(RuntimeError): driver.collect(0, 60)
            collect.assert_not_called()

    def test_dynamic_backend_keeps_required_suites_without_fixed_test_count(self):
        lease = {'source_sha': 'a' * 64, 'build': 'ci222-server-0123456789ab.service',
                 'execution': 'ci222-server-abcdef012345.service',
                 'owner': 'ci222-owner-0123456789ab.service'}
        driver = pilot.BackendJobDriver(Path('/base'), Path('/prepared'), Path('/attempt'),
                                        safety_lease=lease)
        driver.cleanup_confirmed = True
        with patch.object(pilot, 'verify_unit_stopped'), \
             patch.object(Path, 'mkdir'), \
             patch.object(pilot.job_io, 'collect_reports', return_value={'summary': {}, 'passed': True}) as collect:
            self.assertTrue(driver.collect(0, 60)['passed'])
            self.assertIsNone(collect.call_args.kwargs['expected_tests'])

    def test_failed_build_cleanup_still_stops_execution(self):
        driver = pilot.BackendJobDriver(Path('/base'), Path('/prepared'), Path('/attempt'))
        with patch.object(pilot, 'stop_unit', side_effect=[RuntimeError('build cleanup'), None]) as stop:
            with self.assertRaises(RuntimeError): driver.stop(60)
            self.assertEqual([call.args[0] for call in stop.call_args_list],
                             [driver.build_unit, driver.unit])

    def test_canary_failure_also_rejects_interruption_fixture(self):
        source = (HERE / 'ci-server-manager-pilot.py').read_text()
        self.assertIn("if 'fixture_passed' in result: result['fixture_passed'] = False", source)

    def test_backend_lifecycle_waits_for_real_database(self):
        script = pilot.backend_lifecycle_payload()
        self.assertIn('docker ps -q --filter ancestor=pgvector/pgvector:0.8.6-pg17-bookworm', script)
        self.assertIn('kill -0 "$gradle_pid"', script)
        self.assertIn('wait "$gradle_pid" || code=$?', script)
        self.assertIn('clean test spotlessCheck --offline', script)

    def test_backend_accepts_only_validated_snapshot_and_dynamic_count(self):
        source = (HERE / 'ci-server-manager-pilot.py').read_text()
        self.assertIn('source_installer=job_io.install_snapshot', source)
        self.assertIn('expected_tests = 82', source)
        self.assertIn('expected_tests = None', source)
        self.assertIn("glob('ci222-server-*.service')", source)


if __name__ == '__main__':
    unittest.main()
