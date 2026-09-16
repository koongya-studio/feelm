import importlib.util
import hashlib
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent


def load_prepare():
    spec = importlib.util.spec_from_file_location(
        'test_pipeline_mr_prepare_impl', HERE / 'ci-pipeline-mr-prepare.py')
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class PipelinePrepareTests(unittest.TestCase):
    def test_default_is_read_only_plan(self):
        prepare = load_prepare()
        with patch.object(sys, 'argv', ['prepare']), patch.object(prepare, 'prepare') as run:
            self.assertEqual(prepare.main(), 0)
        run.assert_not_called()

    def test_build_inputs_and_base_are_digest_pinned(self):
        prepare = load_prepare()
        self.assertRegex(prepare.BASE_IMAGE, r'@sha256:[0-9a-f]{64}$')
        self.assertRegex(prepare.UV_SHA256, r'^[0-9a-f]{64}$')
        self.assertRegex(prepare.NUMPY_SHA256, r'^[0-9a-f]{64}$')
        self.assertIn('releases.astral.sh/', prepare.UV_URL)
        self.assertIn('files.pythonhosted.org/', prepare.NUMPY_URL)
        dockerfile = prepare.dockerfile()
        self.assertIn('FROM ' + prepare.BASE_IMAGE, dockerfile)
        self.assertIn("uv --version | grep -E '^uv 0[.]12[.]12([[:space:]]|$)'", dockerfile)

    def test_source_requires_reviewed_manifest_and_lock(self):
        prepare = load_prepare()
        project = (ROOT / 'pipeline/spark/pyproject.toml').read_bytes()
        lock = (ROOT / 'pipeline/spark/uv.lock').read_bytes()
        with patch.object(prepare, 'PROJECT_SHA256', hashlib.sha256(project).hexdigest()), \
             patch.object(prepare, 'LOCK_SHA256', hashlib.sha256(lock).hexdigest()):
            inputs = prepare.trusted_source(ROOT)
        self.assertEqual({'pyproject.toml', 'uv.lock'}, set(inputs))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            spark = root / 'pipeline' / 'spark'
            spark.mkdir(parents=True)
            (spark / 'pyproject.toml').write_bytes(b'changed')
            (spark / 'uv.lock').write_bytes(b'changed')
            with self.assertRaisesRegex(RuntimeError, 'reviewed revision'):
                prepare.trusted_source(root)

    def test_reviewed_dependency_hashes_match_embedding_mr(self):
        prepare = load_prepare()
        self.assertEqual(
            '9af26ce74eb1e9ce9de985bc4e686b06f168d120a8346499ed6bfa4b85826150',
            prepare.PROJECT_SHA256)
        self.assertEqual(
            'a61495007822f2456d63aeba01c5b22a0c26eba076aa2a0d79552eb3dc2a6c23',
            prepare.LOCK_SHA256)

    def test_cache_population_is_online_once_then_offline(self):
        prepare = load_prepare()
        online = prepare.uv_sync_command(Path('/source'), Path('/cache'), 'bridge')
        offline = prepare.uv_sync_command(Path('/source'), Path('/cache'), 'none')
        self.assertIn('--network=bridge', online)
        self.assertNotIn('--offline', online)
        self.assertIn('--network=none', offline)
        self.assertIn('--offline', offline)
        self.assertIn('UV_LINK_MODE=copy', offline)

    def test_cache_normalization_materializes_only_internal_links(self):
        prepare = load_prepare()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / 'source'
            source.mkdir()
            target = source / 'content'
            target.write_bytes(b'package')
            link = source / 'linked'
            try:
                link.symlink_to('content')
            except OSError as exc:
                self.skipTest('Symlinks are unavailable: ' + str(exc))
            destination = root / 'destination'
            digest = prepare.normalize_cache(source, destination)
            self.assertEqual(b'package', (destination / 'linked').read_bytes())
            self.assertFalse((destination / 'linked').is_symlink())
            self.assertRegex(digest, r'^[0-9a-f]{64}$')

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / 'source'
            source.mkdir()
            outside = root / 'outside'
            outside.write_bytes(b'outside')
            link = source / 'escape'
            link.symlink_to(outside)
            with self.assertRaisesRegex(RuntimeError, 'escapes'):
                prepare.normalize_cache(source, root / 'destination')

    def test_image_transfer_uses_fixed_rootless_socket_without_shell(self):
        prepare = load_prepare()
        command = prepare.rootless_command('image', 'inspect', prepare.IMAGE)
        self.assertIn('DOCKER_HOST=unix:///run/user/1001/docker.sock', command)
        source = Path(prepare.__file__).read_text()
        self.assertNotIn('shell=True', source)
        self.assertIn("'--network=none'", source)
        self.assertIn('Partial or mismatched Pipeline image installation requires review', source)

    def test_existing_image_is_reused_only_when_both_daemons_match(self):
        prepare = load_prepare()
        identity = 'sha256:' + 'a' * 64
        completed = lambda code, output='': __import__('subprocess').CompletedProcess(
            [], code, stdout=output, stderr='')
        with patch.object(prepare.subprocess, 'run', side_effect=[
                completed(0, identity + '\n'), completed(0, identity + '\n')]):
            self.assertEqual(identity, prepare.existing_image())
        with patch.object(prepare.subprocess, 'run', side_effect=[
                completed(0, identity + '\n'), completed(1)]), \
             self.assertRaisesRegex(RuntimeError, 'Partial or mismatched'):
            prepare.existing_image()


if __name__ == '__main__':
    unittest.main()
