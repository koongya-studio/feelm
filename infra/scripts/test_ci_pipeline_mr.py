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
ROOT = HERE.parent.parent
SPARK = ROOT / 'pipeline' / 'spark'


def load_required(testcase, name, filename):
    path = HERE / filename
    testcase.assertTrue(path.is_file(), 'Missing required implementation: ' + filename)
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def snapshot(entries=None):
    values = {
        'pipeline/spark/.python-version': (SPARK / '.python-version').read_bytes(),
        'pipeline/spark/pyproject.toml': (SPARK / 'pyproject.toml').read_bytes(),
        'pipeline/spark/uv.lock': (SPARK / 'uv.lock').read_bytes(),
        'pipeline/spark/batch/example.py': b'VALUE = 1\n',
        'pipeline/spark/tests/fixtures/example.csv': b'id,value\n1,ok\n',
    }
    values.update(entries or {})
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode='w') as archive:
        for name in ('pipeline/', 'pipeline/spark/', 'pipeline/spark/batch/',
                     'pipeline/spark/tests/', 'pipeline/spark/tests/fixtures/'):
            item = tarfile.TarInfo(name)
            item.type = tarfile.DIRTYPE
            item.mode = 0o755
            archive.addfile(item)
        for name, data in values.items():
            item = tarfile.TarInfo(name)
            item.size = len(data)
            item.mode = 0o644
            archive.addfile(item, io.BytesIO(data))
    return output.getvalue()


class PipelineBoundaryTests(unittest.TestCase):
    def boundary(self):
        return load_required(self, 'test_pipeline_boundary', 'ci-pipeline-mr-boundary.py')

    def test_accepts_nested_spark_project_and_locked_dependencies(self):
        boundary = self.boundary()
        payload = snapshot()
        files, _ = boundary.source_plan(payload, hashlib.sha256(payload).hexdigest())
        self.assertIn('pipeline/spark/pyproject.toml', files)
        self.assertIn('pipeline/spark/uv.lock', files)
        self.assertIn('pipeline/spark/tests/fixtures/example.csv', files)
        self.assertTrue(all(name == 'pipeline' or name.startswith('pipeline/spark')
                            for name in files))

    def test_rejects_out_of_scope_secret_executable_and_link(self):
        boundary = self.boundary()
        cases = (
            snapshot({'pipeline/consumer/outside.py': b'outside\n'}),
            snapshot({'pipeline/spark/.env': b'SECRET=value\n'}),
        )
        for payload in cases:
            with self.subTest(payload=hashlib.sha256(payload).hexdigest()), \
                 self.assertRaises(ValueError):
                boundary.source_plan(payload, hashlib.sha256(payload).hexdigest())

        payload = io.BytesIO(snapshot())
        changed = io.BytesIO()
        with tarfile.open(fileobj=payload, mode='r:') as source, \
             tarfile.open(fileobj=changed, mode='w') as target:
            for member in source:
                data = source.extractfile(member) if member.isfile() else None
                if member.name == 'pipeline/spark/batch/example.py':
                    member.mode = 0o755
                target.addfile(member, data)
        executable = changed.getvalue()
        with self.assertRaises(ValueError):
            boundary.source_plan(executable, hashlib.sha256(executable).hexdigest())

        linked = io.BytesIO()
        with tarfile.open(fileobj=linked, mode='w') as archive:
            item = tarfile.TarInfo('pipeline/spark/link')
            item.type = tarfile.SYMTYPE
            item.linkname = '/etc/passwd'
            archive.addfile(item)
        payload = linked.getvalue()
        with self.assertRaises(ValueError):
            boundary.source_plan(payload, hashlib.sha256(payload).hexdigest())

    def test_rejects_unapproved_registry_and_lock_identity_mismatch(self):
        boundary = self.boundary()
        unsafe_lock = (SPARK / 'uv.lock').read_bytes().replace(
            b'https://pypi.org/simple', b'https://packages.internal.invalid/simple', 1)
        payload = snapshot({'pipeline/spark/uv.lock': unsafe_lock})
        with self.assertRaisesRegex(ValueError, 'registry|source|lock'):
            boundary.source_plan(payload, hashlib.sha256(payload).hexdigest())

        payload = snapshot()
        with self.assertRaisesRegex(ValueError, 'identity|hash|snapshot'):
            boundary.source_plan(payload, '0' * 64)

    def test_rejects_git_lock_source_manifest_override_and_case_collision(self):
        boundary = self.boundary()
        original = (SPARK / 'uv.lock').read_bytes()
        git_source = original.replace(
            b'source = { registry = "https://pypi.org/simple" }',
            b'source = { git = "https://example.invalid/repo.git", '
            b'precise = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" }', 1)
        payload = snapshot({'pipeline/spark/uv.lock': git_source})
        with self.assertRaisesRegex(ValueError, 'source|lock'):
            boundary.source_plan(payload, hashlib.sha256(payload).hexdigest())

        manifest = (SPARK / 'pyproject.toml').read_bytes() + (
            b'\n[tool.uv.sources]\npyspark = { path = "batch" }\n')
        payload = snapshot({'pipeline/spark/pyproject.toml': manifest})
        with self.assertRaisesRegex(ValueError, 'manifest|source'):
            boundary.source_plan(payload, hashlib.sha256(payload).hexdigest())

        payload = snapshot({'pipeline/spark/batch/Example.py': b'VALUE = 2\n'})
        with self.assertRaisesRegex(ValueError, 'collision|duplicate'):
            boundary.source_plan(payload, hashlib.sha256(payload).hexdigest())


class PipelineRunnerTests(unittest.TestCase):
    def runner(self):
        return load_required(self, 'test_pipeline_runner', 'ci-pipeline-mr-runner.py')

    def config(self):
        return {
            'runner_user': 'feelm-ci-test',
            'docker_host': 'unix:///run/user/1001/docker.sock',
            'pipeline_image': 'feelm-pipeline-ci:python3.10-java21-pyspark4.1.3',
            'pipeline_image_id': 'sha256:' + 'a' * 64,
            'dependency_cache': '/var/lib/feelm-ci222/pipeline-cache/cache-v1',
            'dependency_cache_sha256': 'b' * 64,
            'project_manifest_sha256': 'c' * 64,
            'uv_lock_sha256': 'd' * 64,
        }

    def test_fixed_policy_is_offline_bounded_and_has_no_host_socket(self):
        runner = self.runner()
        command = runner.FIXED_COMMAND
        for expected in (
                'uv lock --check --offline', 'uv sync --frozen --offline',
                'uv run --frozen --offline ruff check .',
                'python -m ci_test_groups verify',
                'python -m ci_test_groups unit -vv --durations=20',
                'python -m ci_test_groups spark-als-a-d -vv --durations=20',
                'python -m ci_test_groups spark-als-e-z -vv --durations=20',
                'python -m ci_test_groups spark-other -vv --durations=20',
                'python -m jobs.batch.als_baseline --help',
                'python -m jobs.batch.parquet_roundtrip --help'):
            self.assertIn(expected, command)
        self.assertEqual(command.count('python -m ci_test_groups '), 5)
        self.assertIn('cp --recursive --no-preserve=ownership,mode,timestamps', command)
        self.assertIn('/uv-cache-source/. /uv-cache-work/', command)
        self.assertNotIn('/work/uv-cache', command)
        self.assertNotIn('/tmp/uv-cache', command)
        source = Path(runner.__file__).read_text()
        for expected in (
                "'--network=none'", "'--read-only'", "'--cap-drop=ALL'",
                "'--user', '0:0'",
                "'--security-opt=no-new-privileges'", "'--pids-limit', '1536'",
                "'--memory'", "'--cpus'", 'RuntimeMaxSec=', 'KillMode=control-group',
                'cleanup --container=', "'SPARK_LOCAL_IP=127.0.0.1'",
                "'UV_CACHE_DIR=/uv-cache-work'", "'UV_LINK_MODE=copy'",
                "'SPARK_LOCAL_DIRS=/work/.spark-local'",
                "'type=volume,destination=/work,volume-nocopy'",
                "'type=volume,destination=/uv-cache-work,volume-nocopy'",
                'PIPELINE_MR_SUPERVISOR_ERROR:'):
            self.assertIn(expected, source)
        self.assertNotIn('/var/run/docker.sock', source)
        self.assertNotIn("'--network=host'", source)
        self.assertNotIn("'/work:rw,exec,nodev,nosuid", source)
        self.assertIn("'/tmp:rw,exec,nodev,nosuid,size=1g'", source)
        self.assertIn('timeout=3500', source)
        self.assertIn('RuntimeMaxSec=3600', source)
        self.assertIn('process.wait(3630)', source)

    def test_config_requires_pinned_image_and_dependency_cache_identity(self):
        runner = self.runner()
        config = self.config()
        with tempfile.NamedTemporaryFile(mode='w', delete=False) as output:
            json.dump(config, output)
            path = Path(output.name)
        try:
            info = type('Info', (), {
                'st_mode': 0o100600, 'st_uid': 0, 'st_nlink': 1,
                'st_size': path.stat().st_size})()
            with patch.object(runner.Path, 'lstat', return_value=info), \
                 patch.object(runner.Path, 'read_text', return_value=json.dumps(config)):
                self.assertEqual(runner.read_config(path), config)
            invalid = dict(config, dependency_cache_sha256='not-a-digest')
            with patch.object(runner.Path, 'lstat', return_value=info), \
                 patch.object(runner.Path, 'read_text', return_value=json.dumps(invalid)), \
                 self.assertRaises(ValueError):
                runner.read_config(path)
        finally:
            path.unlink(missing_ok=True)

    def test_source_and_cache_identity_are_verified_before_execution(self):
        runner = self.runner()
        config = self.config()
        payload = snapshot()
        files = {
            'pipeline/spark/pyproject.toml': (SPARK / 'pyproject.toml').read_bytes(),
            'pipeline/spark/uv.lock': (SPARK / 'uv.lock').read_bytes(),
        }
        config['project_manifest_sha256'] = hashlib.sha256(
            files['pipeline/spark/pyproject.toml']).hexdigest()
        config['uv_lock_sha256'] = hashlib.sha256(
            files['pipeline/spark/uv.lock']).hexdigest()
        runner.verify_source_identity(config, files)
        with patch.object(runner.cache_policy, 'verify', return_value='b' * 64) as verify:
            runner.verify_cache_identity(config)
        verify.assert_called_once_with(
            Path(config['dependency_cache']), config['dependency_cache_sha256'])
        changed = dict(config, uv_lock_sha256='e' * 64)
        with self.assertRaisesRegex(ValueError, 'identity'):
            runner.verify_source_identity(changed, files)

    def test_timeout_still_removes_pipeline_container(self):
        runner = self.runner()
        config = self.config()
        inspect = subprocess.CompletedProcess([], 0,
                                              stdout=config['pipeline_image_id'] + '\n')
        removed = subprocess.CompletedProcess([], 0)
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(runner.subprocess, 'run',
                          side_effect=[inspect, subprocess.TimeoutExpired('docker', 3500),
                                       removed]) as run:
            source = Path(directory)
            (source / 'pipeline' / 'spark').mkdir(parents=True)
            with self.assertRaises(subprocess.TimeoutExpired):
                runner.workload(config, source, 'ci352-pipeline-0123456789ab')
        self.assertEqual(run.call_args_list[-1].args[0][-3:],
                         ['rm', '--force', 'ci352-pipeline-0123456789ab'])

    def test_cleanup_rejects_unverifiable_daemon_failure(self):
        runner = self.runner()
        missing = subprocess.CompletedProcess([], 1)
        daemon_ok = subprocess.CompletedProcess([], 0)
        with patch.object(runner.subprocess, 'run',
                          side_effect=[missing, missing, daemon_ok]):
            self.assertEqual(runner.cleanup_container(
                self.config(), 'ci352-pipeline-0123456789ab'), 0)
        daemon_failed = subprocess.CompletedProcess([], 1)
        with patch.object(runner.subprocess, 'run',
                          side_effect=[missing, missing, daemon_failed]), \
             self.assertRaisesRegex(RuntimeError, 'could not be verified'):
            runner.cleanup_container(self.config(), 'ci352-pipeline-0123456789ab')


class PipelineConfigTests(unittest.TestCase):
    def test_default_command_is_plan_only(self):
        configurator = load_required(
            self, 'test_pipeline_config', 'ci-pipeline-mr-config.py')
        with patch.object(sys, 'argv', ['configurator']), \
             patch.object(configurator, 'install') as install:
            self.assertEqual(configurator.main(), 0)
        install.assert_not_called()


class PipelineCachePolicyTests(unittest.TestCase):
    def test_cache_identity_covers_nested_content_and_marker_is_not_self_hashed(self):
        policy = load_required(
            self, 'test_pipeline_cache_policy', 'ci-pipeline-cache-policy.py')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            nested = root / 'archive-v0'
            nested.mkdir()
            package = nested / 'package.whl'
            package.write_bytes(b'reviewed-package')
            package.chmod(0o444)
            nested.chmod(0o555)
            root.chmod(0o555)
            digest = policy.identity(root)
            root.chmod(0o755)
            marker = root / policy.MARKER
            marker.write_bytes((digest + '\n').encode('ascii'))
            marker.chmod(0o444)
            root.chmod(0o555)
            self.assertEqual(policy.verify(root, digest), digest)
            package.chmod(0o644)
            package.write_bytes(b'changed-package')
            package.chmod(0o444)
            with self.assertRaisesRegex(RuntimeError, 'content identity'):
                policy.verify(root, digest)
            root.chmod(0o755)
            nested.chmod(0o755)
            package.chmod(0o644)
            marker.chmod(0o644)


if __name__ == '__main__':
    unittest.main()
