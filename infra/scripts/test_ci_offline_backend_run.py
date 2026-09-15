import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('offline_run', Path(__file__).with_name('ci-offline-backend-run.py'))
run = importlib.util.module_from_spec(spec)
spec.loader.exec_module(run)


class OfflineBackendTests(unittest.TestCase):
    def test_plan_no_execution(self):
        with patch('sys.argv', ['runner']), patch.object(run, 'run') as execute:
            self.assertEqual(run.main(), 0)
            execute.assert_not_called()

    def test_confirmation_required(self):
        with patch('sys.argv', ['runner', 'run', '--prepared-dir', '/tmp/no']), patch.object(run, 'run') as execute:
            with self.assertRaises(SystemExit):
                run.main()
            execute.assert_not_called()

    def test_offline_environment(self):
        env = run.environment(Path('/prepared'), {'net': 123, 'user': 456, 'mnt': 789})
        self.assertEqual(env['DOCKER_HOST'], 'unix:///run/feelm-ci222-offline/docker.sock')
        self.assertEqual(env['TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE'], '/run/feelm-ci222-offline/docker.sock')
        self.assertEqual(env['TESTCONTAINERS_RYUK_DISABLED'], 'false')
        self.assertEqual(env['PROBE_NS_net'], '123')
        self.assertNotIn('HTTP_PROXY', env)

    def test_full_offline_tasks_and_no_dns_mount(self):
        self.assertIn("'clean', 'test', 'spotlessCheck', '--offline'", run.CHILD)
        self.assertNotIn('with private_dns()', run.CHILD)
        self.assertIn('verify_namespace_identity()', run.CHILD)
        compile(run.CHILD, '<offline-child>', 'exec')

    def test_regular_files(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'file').write_text('data')
            self.assertEqual(set(run.regular_files(root)), {'file'})

    def test_archive_hash_failure(self):
        with self.assertRaisesRegex(ValueError, 'archive hash mismatch'):
            run.validate_source(Path('/unused'), b'wrong archive')

    def test_existing_execution_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            prepared = root / ('a' * 32)
            prepared.mkdir()
            (prepared / 'execution-started.json').write_text('{}')
            with patch.object(run.prep, 'OUTPUT', root), self.assertRaisesRegex(ValueError, 'previous run'):
                run.inspect_inputs(prepared)

    def test_images_checked_readonly(self):
        manifest = {'images': {tag: {'digest': digest, 'id': 'id1', 'os': 'linux', 'architecture': 'amd64'}
                               for tag, digest in run.prep.IMAGES.items()}}
        calls = []
        def execute(prefix, *args):
            calls.append(args)
            data = {'DockerRootDir': run.port.ROOT, 'SecurityOptions': ['name=rootless']} if args[0] == 'info' else {
                'Id': 'id1', 'Os': 'linux', 'Architecture': 'amd64'}
            return subprocess.CompletedProcess([], 0, '' if args[0] == 'ps' else json.dumps(data))
        with patch.object(run.network, 'execute', side_effect=execute):
            run.inspect_images([], manifest)
            manifest['images']['redis:8-alpine']['id'] = 'different'
            with self.assertRaisesRegex(ValueError, 'image mismatch'):
                run.inspect_images([], manifest)
        self.assertTrue(all(args[0] in ('info', 'ps', 'image') for args in calls))
        self.assertTrue(all(args[1] == 'inspect' for args in calls if args[0] == 'image'))


if __name__ == '__main__':
    unittest.main()
