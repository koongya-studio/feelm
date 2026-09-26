import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec); sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


boundary = load('test_frontend_boundary', 'ci-frontend-mr-boundary.py')
runner = load('test_frontend_runner', 'ci-frontend-mr-runner.py')
configurator = load('test_frontend_config', 'ci-frontend-mr-config.py')


def snapshot(entries=None, scripts=None, lock=None):
    scripts = scripts or boundary.EXPECTED_SCRIPTS
    lock = lock or {'lockfileVersion': 3, 'packages': {}}
    values = {
        'frontend/package.json': json.dumps({'scripts': scripts}).encode(),
        'frontend/package-lock.json': json.dumps(lock).encode(),
        'frontend/src/App.tsx': b'export default function App() { return null }',
    }
    values.update(entries or {})
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode='w') as archive:
        for name in ('frontend/', 'frontend/src/'):
            item = tarfile.TarInfo(name); item.type = tarfile.DIRTYPE; item.mode = 0o755
            archive.addfile(item)
        for name, data in values.items():
            item = tarfile.TarInfo(name); item.size = len(data); item.mode = 0o644
            archive.addfile(item, io.BytesIO(data))
    return output.getvalue()


class FrontendBoundaryTests(unittest.TestCase):
    def test_accepts_frontend_source_and_fixed_npm_scripts(self):
        payload = snapshot()
        files, _ = boundary.source_plan(payload, hashlib.sha256(payload).hexdigest())
        self.assertEqual(set(boundary.EXPECTED_SCRIPTS),
                         set(json.loads(files['frontend/package.json'])['scripts']))

    def test_rejects_changed_script_link_and_out_of_scope_file(self):
        changed = dict(boundary.EXPECTED_SCRIPTS, build='curl http://metadata/')
        payload = snapshot(scripts=changed)
        with self.assertRaisesRegex(ValueError, 'scripts differ'):
            boundary.source_plan(payload, hashlib.sha256(payload).hexdigest())

        output = io.BytesIO()
        with tarfile.open(fileobj=output, mode='w') as archive:
            item = tarfile.TarInfo('frontend/link'); item.type = tarfile.SYMTYPE
            item.linkname = '/etc/passwd'; archive.addfile(item)
        payload = output.getvalue()
        with self.assertRaises(ValueError):
            boundary.source_plan(payload, hashlib.sha256(payload).hexdigest())

        payload = snapshot({'backend/file': b'outside'})
        with self.assertRaises(ValueError):
            boundary.source_plan(payload, hashlib.sha256(payload).hexdigest())

    def test_rejects_non_registry_lock_resolution(self):
        lock = {'lockfileVersion': 3, 'packages': {
            'node_modules/example': {'resolved': 'https://internal.invalid/a.tgz',
                                     'integrity': 'sha512-value'}}}
        payload = snapshot(lock=lock)
        with self.assertRaisesRegex(ValueError, 'outside registry'):
            boundary.source_plan(payload, hashlib.sha256(payload).hexdigest())

    def test_rejects_lock_without_integrity_and_executable_source(self):
        lock = {'lockfileVersion': 3, 'packages': {
            'node_modules/example': {'resolved': 'https://registry.npmjs.org/example/-/example-1.tgz'}}}
        payload = snapshot(lock=lock)
        with self.assertRaisesRegex(ValueError, 'integrity is missing'):
            boundary.source_plan(payload, hashlib.sha256(payload).hexdigest())

        payload = snapshot()
        source = io.BytesIO(payload)
        output = io.BytesIO()
        with tarfile.open(fileobj=source, mode='r:') as original, \
             tarfile.open(fileobj=output, mode='w') as changed:
            for member in original:
                data = original.extractfile(member) if member.isfile() else None
                if member.name == 'frontend/src/App.tsx':
                    member.mode = 0o755
                changed.addfile(member, data)
        payload = output.getvalue()
        with self.assertRaisesRegex(ValueError, 'Unsupported'):
            boundary.source_plan(payload, hashlib.sha256(payload).hexdigest())

    def test_rejects_lifecycle_hooks_for_fixed_scripts(self):
        for hook in ('prelint', 'pretest:run', 'posttest:run'):
            scripts = dict(boundary.EXPECTED_SCRIPTS)
            scripts[hook] = 'curl https://example.invalid/'
            payload = snapshot(scripts=scripts)
            with self.subTest(hook=hook), self.assertRaisesRegex(ValueError, 'lifecycle hooks'):
                boundary.source_plan(payload, hashlib.sha256(payload).hexdigest())


class FrontendRunnerTests(unittest.TestCase):
    def config(self):
        return {'runner_user': 'feelm-ci-test',
                'docker_host': 'unix:///run/user/1001/docker.sock',
                'node_image': 'node:22.22.0-alpine', 'node_image_id': 'sha256:' + 'a' * 64}

    def test_fixed_container_has_no_socket_secret_or_host_network(self):
        command = runner.docker_command(self.config(), 'run')
        self.assertIn('env', command)
        unit = runner.unit_command(self.config(), Path('/var/tmp/source'),
                                   'ci222-owner-0123456789ab.service',
                                   'ci330-frontend-0123456789ab')
        self.assertIn('--property=KillMode=control-group', unit)
        self.assertTrue(any('ExecStopPost=' in argument and ' cleanup --container=' in argument
                            for argument in unit))
        source = Path(runner.__file__).read_text()
        self.assertIn("'--network=bridge'", source)
        self.assertIn("'--read-only'", source)
        self.assertIn("'--interactive'", source)
        self.assertIn("'/work:rw,exec,nodev,nosuid,size=1536m'", source)
        self.assertIn("'/tmp:rw,nodev,nosuid,size=256m'", source)
        self.assertNotIn("'--volume'", source)
        self.assertIn('tar -xf - -C /work', source)
        self.assertIn("'--cap-drop=ALL'", source)
        self.assertIn("'--security-opt=no-new-privileges'", source)
        self.assertIn('npm ci --ignore-scripts', runner.FIXED_COMMAND)
        self.assertNotIn('npm run', runner.FIXED_COMMAND)
        for command in ('eslint/bin/eslint.js .', 'prettier/bin/prettier.cjs --check .',
                        'vitest/vitest.mjs run --maxWorkers=2', 'typescript/bin/tsc -b', 'vite/bin/vite.js build'):
            self.assertIn('node ./node_modules/' + command, runner.FIXED_COMMAND)
        self.assertNotIn('./node_modules/.bin/', runner.FIXED_COMMAND)
        self.assertNotIn('/var/run/docker.sock', source)
        self.assertIn('attempt.chmod(0o711)', source)
        self.assertEqual(runner.RUNTIME_ROOT, Path('/run'))
        self.assertIn("'--property=PrivateTmp=yes'", source)
        self.assertNotIn("dir='/var/tmp'", source)
        self.assertNotIn('os.chown', Path(boundary.__file__).read_text())

    def test_guard_must_be_active_and_owned(self):
        inactive = subprocess.CompletedProcess([], 3)
        with patch.object(runner.subprocess, 'run', return_value=inactive), \
             patch.object(runner.network, 'owned_table') as owned, \
             self.assertRaisesRegex(RuntimeError, 'not active'):
            runner.guard_ready()
        owned.assert_not_called()

    def test_container_archive_omits_readonly_root_and_normalizes_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory); frontend = source / 'frontend'; frontend.mkdir()
            (frontend / '.dockerignore').write_text('node_modules\n')
            nested = frontend / 'src'; nested.mkdir(); (nested / 'App.tsx').write_text('export {}')
            frontend.chmod(0o555); nested.chmod(0o555)
            (frontend / '.dockerignore').chmod(0o444); (nested / 'App.tsx').chmod(0o444)
            output = io.BytesIO(); runner.write_container_archive(source, output)
        with tarfile.open(fileobj=io.BytesIO(output.getvalue()), mode='r:') as archive:
            members = archive.getmembers()
        self.assertNotIn('.', [member.name for member in members])
        self.assertIn('.dockerignore', [member.name for member in members])
        for member in members:
            self.assertEqual(member.mode, 0o755 if member.isdir() else 0o644)

    def test_image_identity_is_checked_before_container_run_and_cleanup(self):
        config = self.config()
        inspect = subprocess.CompletedProcess([], 0, stdout=config['node_image_id'] + '\n')
        executed = subprocess.CompletedProcess([], 0)
        cleaned = subprocess.CompletedProcess([], 0)
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(runner.subprocess, 'run', side_effect=[inspect, executed, cleaned]) as run:
            source = Path(directory); (source / 'frontend').mkdir()
            self.assertEqual(runner.workload(config, source, 'ci330-frontend-0123456789ab'), 0)
        container = run.call_args_list[1].args[0]
        self.assertIn('--pull=never', container)
        self.assertIn('--network=bridge', container)
        self.assertIn('--interactive', container)
        self.assertNotIn('--volume', container)
        self.assertIn('/work:rw,exec,nodev,nosuid,size=1536m', container)
        self.assertIn('/tmp:rw,nodev,nosuid,size=256m', container)
        self.assertIn(config['node_image_id'], container)
        self.assertNotIn(config['node_image'], container)
        self.assertNotIn('/var/run/docker.sock', ' '.join(container))
        self.assertEqual(run.call_args_list[2].args[0][-3:], ['rm', '--force',
                                                             'ci330-frontend-0123456789ab'])

    def test_timeout_still_removes_container(self):
        config = self.config()
        inspect = subprocess.CompletedProcess([], 0, stdout=config['node_image_id'] + '\n')
        removed = subprocess.CompletedProcess([], 0)
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(runner.subprocess, 'run',
                          side_effect=[inspect, subprocess.TimeoutExpired('docker', 850), removed]) as run:
            source = Path(directory); (source / 'frontend').mkdir()
            with self.assertRaises(subprocess.TimeoutExpired):
                runner.workload(config, source, 'ci330-frontend-0123456789ab')
        self.assertEqual(run.call_args_list[-1].args[0][-3:],
                         ['rm', '--force', 'ci330-frontend-0123456789ab'])

    def test_cleanup_is_idempotent_but_rejects_unverifiable_daemon_failure(self):
        missing = subprocess.CompletedProcess([], 1)
        daemon_ok = subprocess.CompletedProcess([], 0)
        with patch.object(runner.subprocess, 'run', side_effect=[missing, missing, daemon_ok]):
            self.assertEqual(runner.cleanup_container(
                self.config(), 'ci330-frontend-0123456789ab'), 0)
        daemon_failed = subprocess.CompletedProcess([], 1)
        with patch.object(runner.subprocess, 'run',
                          side_effect=[missing, missing, daemon_failed]), \
             self.assertRaisesRegex(RuntimeError, 'could not be verified'):
            runner.cleanup_container(self.config(), 'ci330-frontend-0123456789ab')


class FrontendConfigTests(unittest.TestCase):
    def test_default_command_is_plan_only(self):
        with patch.object(sys, 'argv', ['configurator']), \
             patch.object(configurator, 'install') as install:
            self.assertEqual(configurator.main(), 0)
        install.assert_not_called()

    def test_config_and_runner_accept_the_same_image_policy(self):
        for image in ('node:22.22.0-alpine', 'node:22.22.1-alpine3.21'):
            self.assertIsNotNone(__import__('re').fullmatch(configurator.IMAGE_PATTERN, image))
            config = FrontendRunnerTests().config(); config['node_image'] = image
            with tempfile.NamedTemporaryFile(mode='w', delete=False) as output:
                json.dump(config, output)
                path = Path(output.name)
            try:
                with patch.object(runner.Path, 'lstat') as lstat:
                    lstat.return_value = type('Info', (), {
                        'st_mode': 0o100600, 'st_uid': 0, 'st_nlink': 1,
                        'st_size': path.stat().st_size})()
                    with patch.object(runner.Path, 'read_text', return_value=json.dumps(config)):
                        self.assertEqual(runner.read_config(path)['node_image'], image)
            finally:
                path.unlink(missing_ok=True)

    def test_rejects_old_and_unpinned_node_images(self):
        for image in ('node:20.19-alpine', 'node:22.21.0-alpine', 'node:22-alpine',
                      'node:22.22-alpine', 'node:latest'):
            self.assertIsNone(__import__('re').fullmatch(configurator.IMAGE_PATTERN, image))
            config = FrontendRunnerTests().config(); config['node_image'] = image
            with patch.object(runner.Path, 'lstat') as lstat:
                lstat.return_value = type('Info', (), {
                    'st_mode': 0o100600, 'st_uid': 0, 'st_nlink': 1, 'st_size': 256})()
                with patch.object(runner.Path, 'read_text', return_value=json.dumps(config)):
                    with self.assertRaisesRegex(RuntimeError, 'Unreviewed Node'):
                        runner.read_config()


if __name__ == '__main__':
    unittest.main()
