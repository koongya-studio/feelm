import contextlib
import importlib.util
import io
import json
from pathlib import Path
import stat
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('offline', Path(__file__).with_name('ci-backend-offline-prepare.py'))
offline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(offline)


def cache_fixture(root):
    for name in ('wrapper/dists/gradle-9.7.1-bin/hash/gradle-9.7.1/bin/gradle',
                 'caches/modules-2/files-2.1/example.jar', 'caches/modules-2/modules-2.lock',
                 'gradle.properties', 'init.d/local.gradle', 'daemon/example.out.log'):
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('fixture')


class OfflinePreparationTests(unittest.TestCase):
    def test_plan_does_not_inspect_or_prepare(self):
        with patch.object(offline.sys, 'argv', ['offline']), patch.object(offline, 'inspect') as inspect, \
                patch.object(offline, 'prepare') as prepare, contextlib.redirect_stdout(io.StringIO()):
            offline.main()
        inspect.assert_not_called()
        prepare.assert_not_called()

    def test_no_run_action_or_unconfirmed_prepare(self):
        for args in (['offline', 'run'], ['offline', 'prepare']):
            with patch.object(offline.sys, 'argv', args), patch.object(offline, 'prepare') as prepare, \
                    contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                offline.main()
            prepare.assert_not_called()

    def test_inspect_and_prepare_require_explicit_source_run(self):
        for action in ('inspect', 'prepare'):
            with patch.object(offline.sys, 'argv', ['offline', action]), \
                    contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                offline.main()

    def test_selected_cache_excludes_personal_state_and_locks(self):
        with tempfile.TemporaryDirectory() as directory:
            cache_fixture(Path(directory))
            files, size = offline.cache_inventory(Path(directory))
            self.assertEqual(len(files), 2)
            self.assertEqual(size, 14)
            self.assertFalse(any('init.d' in x or x.endswith('.lock') or 'properties' in x for x in files))

    def test_missing_distribution_or_dependency_cache_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'caches').mkdir()
            (root / 'wrapper/dists').mkdir(parents=True)
            with self.assertRaises(ValueError):
                offline.cache_inventory(root)

    def test_cache_size_limit_fails(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(offline, 'MAX_CACHE', 1):
            cache_fixture(Path(directory))
            with self.assertRaises(ValueError):
                offline.cache_inventory(Path(directory))

    def test_cache_rejects_linked_file(self):
        with tempfile.TemporaryDirectory() as directory:
            cache_fixture(Path(directory))
            original = Path.lstat
            def changed(path, *args, **kwargs):
                if path.name == 'example.jar':
                    return SimpleNamespace(st_mode=stat.S_IFLNK | 0o777)
                return original(path, *args, **kwargs)
            with patch.object(Path, 'lstat', changed), self.assertRaises(ValueError):
                offline.cache_inventory(Path(directory))

    def test_image_inspection_only_reads_and_requires_digest(self):
        info = {'SecurityOptions': ['name=rootless'], 'DockerRootDir': '/home/feelm-ci-test/.local/share/docker'}
        payloads = [SimpleNamespace(stdout=json.dumps(info)), SimpleNamespace(stdout='')]
        payloads += [SimpleNamespace(stdout=json.dumps({'RepoDigests': [digest], 'Id': 'sha256:id',
                                                        'Os': 'linux', 'Architecture': 'amd64'}))
                     for digest in offline.IMAGES.values()]
        with patch.object(offline.pilot.network, 'execute', side_effect=payloads) as execute:
            self.assertEqual(len(offline.image_inventory(['docker'])), 4)
        self.assertEqual(
            offline.IMAGES['apache/kafka:4.3.1'],
            'apache/kafka@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837')
        self.assertTrue(all(call.args[1] in ('info', 'ps', 'image') for call in execute.call_args_list))
        payloads[2] = SimpleNamespace(stdout=json.dumps({'RepoDigests': []}))
        with patch.object(offline.pilot.network, 'execute', side_effect=payloads), self.assertRaises(ValueError):
            offline.image_inventory(['docker'])

    def test_existing_container_prevents_preparation(self):
        info = {'SecurityOptions': ['name=rootless'], 'DockerRootDir': '/home/feelm-ci-test/.local/share/docker'}
        with patch.object(offline.pilot.network, 'execute', side_effect=[
                SimpleNamespace(stdout=json.dumps(info)), SimpleNamespace(stdout='container')]), \
                self.assertRaises(ValueError):
            offline.image_inventory(['docker'])

    def test_preparation_copies_without_modifying_source_or_running_gradle(self):
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
            root = Path(directory)
            cache = root / 'source'
            cache_fixture(cache)
            files, size = offline.cache_inventory(cache)
            with patch.object(offline, 'OUTPUT', root / 'out'), \
                    patch.object(offline, 'inspect', return_value=(b'archive', cache, files, size, {})), \
                    patch.object(offline.shutil, 'disk_usage', return_value=SimpleNamespace(free=10 * 1024**3)), \
                    patch.object(offline.pilot, 'extract_snapshot'), patch.object(offline.pilot, 'SUPPORT_FILES', ()), \
                    patch.object(offline.pilot.network, 'execute') as execute:
                offline.prepare(SimpleNamespace(source_run=str(root)))
            execute.assert_not_called()
            manifest_file = next((root / 'out').glob('*/manifest.json'))
            manifest = json.loads(manifest_file.read_text())
            self.assertFalse(manifest['tests_executed'])
            self.assertFalse(manifest['docker_images_exported'])
            self.assertEqual(manifest['state'], 'WAITING_DOCKER_BOUNDARY_REVIEW')
            copied, _ = offline.cache_inventory(manifest_file.parent / 'home/.gradle')
            self.assertEqual(copied, files)
            self.assertEqual(offline.cache_inventory(cache)[0], files)

    def test_full_tasks_and_offline_flag_preserved(self):
        self.assertEqual(offline.GRADLE_ARGS[1:4], ['clean', 'test', 'spotlessCheck'])
        self.assertIn('--offline', offline.GRADLE_ARGS)
        self.assertNotIn('--tests', offline.GRADLE_ARGS)

    def test_changed_copy_never_gets_complete_manifest(self):
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
            root = Path(directory)
            cache = root / 'source'
            cache_fixture(cache)
            files, size = offline.cache_inventory(cache)
            with patch.object(offline, 'OUTPUT', root / 'out'), \
                    patch.object(offline, 'inspect', return_value=(b'archive', cache, files, size, {})), \
                    patch.object(offline.shutil, 'disk_usage', return_value=SimpleNamespace(free=10 * 1024**3)), \
                    patch.object(offline.pilot, 'extract_snapshot'), \
                    patch.object(offline.shutil, 'copyfile', side_effect=lambda src, dst: dst.write_text('changed')), \
                    self.assertRaises(ValueError):
                offline.prepare(SimpleNamespace(source_run=str(root)))
            self.assertEqual(list((root / 'out').glob('*/manifest.json')), [])


if __name__ == '__main__':
    unittest.main()
