import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
import urllib.error
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('gitlab_adapter', HERE / 'ci-gitlab-job-adapter.py')
adapter = importlib.util.module_from_spec(spec); spec.loader.exec_module(adapter)

PIPELINE_SCRIPTS = {
    'pipeline-unit-test': [
        'cd pipeline/spark',
        'uv sync --frozen',
        'uv run ruff check .',
        'uv run python -m ci_test_groups verify',
        'uv run python -m ci_test_groups unit -vv --durations=20',
        'uv run python -m jobs.batch.als_baseline --help',
        'uv run python -m jobs.batch.parquet_roundtrip --help',
    ],
    'pipeline-spark-als-a-d-test': [
        'cd pipeline/spark',
        'uv sync --frozen',
        'uv run python -m ci_test_groups spark-als-a-d -vv --durations=20',
    ],
    'pipeline-spark-als-e-z-test': [
        'cd pipeline/spark',
        'uv sync --frozen',
        'uv run python -m ci_test_groups spark-als-e-z -vv --durations=20',
    ],
    'pipeline-spark-other-test': [
        'cd pipeline/spark',
        'uv sync --frozen',
        'uv run python -m ci_test_groups spark-other -vv --durations=20',
    ],
}
CONSUMER_SCRIPT = [
    'cd pipeline/consumer',
    'uv sync --frozen',
    'uv run ruff check .',
    'uv run pytest -v',
]


def response(script=None, job_name='backend-mr-test'):
    policies = {
        'backend-mr-test': adapter.EXPECTED_SCRIPT,
        'frontend-mr-test': adapter.FRONTEND_EXPECTED_SCRIPT,
        'consumer-mr-test': CONSUMER_SCRIPT,
        **PIPELINE_SCRIPTS,
    }
    return {
        'id': 55, 'token': 'valid-job-token-123456789',
        'job_info': {'name': job_name, 'stage': 'test', 'project_id': 99},
        'git_info': {'repo_url': 'https://gitlab-ci-token:hidden@lab.ssafy.com/'
                     's15-bigdata-dist-sub1/S15P21E106.git',
                     'sha': 'a' * 40, 'ref_type': 'branch'},
        'steps': [{'name': 'script', 'script': script or policies[job_name]}],
        'services': [], 'artifacts': [], 'cache': [], 'dependencies': [], 'image': {'name': ''},
    }


def cache_response():
    value = response(adapter.CACHE_PREPARE_SCRIPT)
    value['job_info'].update(name=adapter.CACHE_PREPARE_JOB, stage='verify')
    return value


def archive(entries):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode='w') as stream:
        for name, data, mode in entries:
            item = tarfile.TarInfo(name); item.mode = mode
            item.type = tarfile.DIRTYPE if name.endswith('/') else tarfile.REGTYPE
            item.size = 0 if item.isdir() else len(data)
            stream.addfile(item, None if item.isdir() else io.BytesIO(data))
    return output.getvalue()


class AdapterTests(unittest.TestCase):
    def test_backend_mr_job_disables_runner_dependencies(self):
        config = (HERE.parent.parent / '.gitlab/ci/backend.yml').read_text()
        job = re.search(r'(?ms)^backend-mr-test:\n(?P<body>.*?)(?=^\S|\Z)', config)
        self.assertIsNotNone(job)
        self.assertIn('  dependencies: []\n', job.group('body'))

    def test_context_accepts_only_fixed_job_and_script(self):
        result = adapter.validate_job_response(response())
        self.assertEqual(result['project_id'], 99)
        with self.assertRaises(adapter.PolicyError):
            adapter.validate_job_response(response(['curl http://metadata/']))
        changed = response(); changed['services'] = [{'name': 'docker'}]
        with self.assertRaises(adapter.PolicyError): adapter.validate_job_response(changed)
        cache = adapter.validate_job_response(cache_response())
        self.assertEqual(cache['job_name'], adapter.CACHE_PREPARE_JOB)
        frontend = adapter.validate_job_response(response(job_name='frontend-mr-test'))
        self.assertEqual(frontend['service'], 'frontend')
        consumer = adapter.validate_job_response(response(job_name='consumer-mr-test'))
        self.assertEqual(consumer['service'], 'consumer')

    def test_consumer_context_archive_and_runner_are_fixed(self):
        self.assertEqual(adapter.CONSUMER_EXPECTED_SCRIPT, CONSUMER_SCRIPT)
        context = response(job_name='consumer-mr-test')
        changed = response(CONSUMER_SCRIPT + ['curl http://metadata/'],
                           job_name='consumer-mr-test')
        with self.assertRaisesRegex(adapter.PolicyError, 'fixed isolated policy'):
            adapter.validate_job_response(changed)

        root = HERE.parent.parent
        consumer = root / 'pipeline' / 'consumer'
        payload = archive([
            ('source/', b'', 0o755),
            ('source/pipeline/', b'', 0o755),
            ('source/pipeline/consumer/', b'', 0o755),
            ('source/pipeline/consumer/.python-version',
             (consumer / '.python-version').read_bytes(), 0o644),
            ('source/pipeline/consumer/pyproject.toml',
             (consumer / 'pyproject.toml').read_bytes(), 0o644),
            ('source/pipeline/consumer/uv.lock',
             (consumer / 'uv.lock').read_bytes(), 0o644),
            ('source/pipeline/consumer/Dockerfile',
             (consumer / 'Dockerfile').read_bytes(), 0o644),
            ('source/pipeline/consumer/.dockerignore',
             (consumer / '.dockerignore').read_bytes(), 0o644),
            ('source/pipeline/consumer/src/feelm_consumer/example.py', b'VALUE = 1\n', 0o644),
        ])
        result, digest = adapter.normalized_service_archive(payload, 'consumer')
        files, _ = adapter.consumer_boundary.source_plan(result, digest)
        self.assertIn('pipeline/consumer/uv.lock', files)

        source = b'validated-consumer-source'
        source_digest = hashlib.sha256(source).hexdigest()
        with patch.object(adapter, 'read_private_config',
                          return_value=({}, Path('/prepared'))) as private_config, \
             patch.object(adapter, 'verify_current_job', return_value={'pipeline': {'id': 1}}), \
             patch.object(adapter, 'verify_merge_request', return_value={'iid': 98}), \
             patch.object(adapter, 'fetch_merge_request_detail', return_value={'iid': 98}), \
             patch.object(adapter, 'dependency_changes') as dependency, \
             patch.object(adapter, 'fetch_source',
                          return_value=(source, source_digest)) as fetch, \
             patch.object(adapter.consumer_runner, 'start', return_value=0) as runner, \
             patch.object(adapter.safety, 'start') as backend:
            self.assertEqual(adapter.run_job(context, opener=object()), 0)
        dependency.assert_not_called()
        private_config.assert_called_once_with(adapter.CONFIG, require_prepared=False)
        fetch.assert_called_once_with(adapter.validate_job_response(context), 'consumer')
        runner.assert_called_once()
        backend.assert_not_called()

    def test_frontend_archive_accepts_only_validated_frontend_files(self):
        package = json.dumps({'scripts': adapter.frontend_boundary.EXPECTED_SCRIPTS}).encode()
        lock = json.dumps({'lockfileVersion': 3, 'packages': {}}).encode()
        payload = archive([
            ('source/', b'', 0o755),
            ('source/frontend/package.json', package, 0o644),
            ('source/frontend/package-lock.json', lock, 0o644),
            ('source/frontend/src/App.tsx', b'export default 1', 0o644),
        ])
        result, digest = adapter.normalized_service_archive(payload, 'frontend')
        files, _ = adapter.frontend_boundary.source_plan(result, digest)
        self.assertIn('frontend/src/App.tsx', files)

        outside = archive([
            ('source/frontend/package.json', package, 0o644),
            ('source/frontend/package-lock.json', lock, 0o644),
            ('source/backend/secret.txt', b'outside', 0o644),
        ])
        with self.assertRaisesRegex(adapter.PolicyError, 'out-of-scope'):
            adapter.normalized_service_archive(outside, 'frontend')

    def test_pipeline_context_accepts_only_fixed_job_group_and_script(self):
        self.assertEqual(adapter.PIPELINE_JOB_SCRIPTS, PIPELINE_SCRIPTS)
        for job_name, script in PIPELINE_SCRIPTS.items():
            with self.subTest(job_name=job_name):
                result = adapter.validate_job_response(response(script, job_name=job_name))
                self.assertEqual(result['service'], 'pipeline')
                self.assertEqual(result['pipeline_group'], adapter.PIPELINE_JOB_GROUPS[job_name])
                changed = response(script + ['curl http://metadata/'], job_name=job_name)
                with self.assertRaisesRegex(adapter.PolicyError, 'fixed isolated policy'):
                    adapter.validate_job_response(changed)
        swapped = response(
            PIPELINE_SCRIPTS['pipeline-spark-other-test'], job_name='pipeline-unit-test')
        with self.assertRaisesRegex(adapter.PolicyError, 'fixed isolated policy'):
            adapter.validate_job_response(swapped)
        with self.assertRaisesRegex(adapter.PolicyError, 'fixed isolated'):
            adapter.validate_job_response(response(
                PIPELINE_SCRIPTS['pipeline-unit-test'], job_name='pipeline-mr-test'))

    def test_pipeline_archive_contains_only_nested_spark_project(self):
        self.assertTrue(hasattr(adapter, 'pipeline_boundary'),
                        'GitLab adapter must load the Pipeline source boundary')
        root = HERE.parent.parent
        spark = root / 'pipeline' / 'spark'
        payload = archive([
            ('source/', b'', 0o755),
            ('source/README.md', b'outside\n', 0o644),
            ('source/pipeline/', b'', 0o755),
            ('source/pipeline/spark/', b'', 0o755),
            ('source/pipeline/spark/.python-version',
             (spark / '.python-version').read_bytes(), 0o644),
            ('source/pipeline/spark/pyproject.toml',
             (spark / 'pyproject.toml').read_bytes(), 0o644),
            ('source/pipeline/spark/uv.lock', (spark / 'uv.lock').read_bytes(), 0o644),
            ('source/pipeline/spark/batch/example.py', b'VALUE = 1\n', 0o644),
        ])
        result, digest = adapter.normalized_service_archive(payload, 'pipeline')
        with tarfile.open(fileobj=io.BytesIO(result), mode='r:') as normalized:
            names = {item.name.rstrip('/') for item in normalized}
        self.assertIn('pipeline/spark/pyproject.toml', names)
        self.assertIn('pipeline/spark/batch/example.py', names)
        self.assertNotIn('README.md', names)
        self.assertRegex(digest, r'^[0-9a-f]{64}$')

    def test_frontend_job_routes_only_validated_source_to_frontend_runner(self):
        context = response(job_name='frontend-mr-test')
        source = b'validated-frontend-source'
        digest = hashlib.sha256(source).hexdigest()
        with patch.object(adapter, 'read_private_config',
                          return_value=({}, Path('/prepared'))) as private_config, \
             patch.object(adapter, 'verify_current_job', return_value={'pipeline': {'id': 1}}), \
             patch.object(adapter, 'verify_merge_request', return_value={'iid': 98}), \
             patch.object(adapter, 'fetch_merge_request_detail', return_value={'iid': 98}), \
             patch.object(adapter, 'dependency_changes') as dependency, \
             patch.object(adapter, 'fetch_source', return_value=(source, digest)) as fetch, \
             patch.object(adapter.frontend_runner, 'start', return_value=0) as frontend, \
             patch.object(adapter.safety, 'start') as backend:
            self.assertEqual(adapter.run_job(context, opener=object()), 0)
        dependency.assert_not_called()
        private_config.assert_called_once_with(adapter.CONFIG, require_prepared=False)
        fetch.assert_called_once_with(adapter.validate_job_response(context), 'frontend')
        frontend.assert_called_once()
        backend.assert_not_called()

    def test_pipeline_job_routes_only_validated_source_to_pipeline_runner(self):
        self.assertTrue(hasattr(adapter, 'pipeline_runner'),
                        'GitLab adapter must load the installed Pipeline runner')
        job_name = 'pipeline-spark-other-test'
        context = response(PIPELINE_SCRIPTS[job_name], job_name=job_name)
        source = b'validated-pipeline-source'
        digest = hashlib.sha256(source).hexdigest()
        with patch.object(adapter, 'read_private_config',
                          return_value=({}, Path('/prepared'))) as private_config, \
             patch.object(adapter, 'verify_current_job', return_value={'pipeline': {'id': 1}}), \
             patch.object(adapter, 'verify_merge_request', return_value={'iid': 98}), \
             patch.object(adapter, 'fetch_merge_request_detail', return_value={'iid': 98}), \
             patch.object(adapter, 'dependency_changes') as dependency, \
             patch.object(adapter, 'fetch_source', return_value=(source, digest)) as fetch, \
             patch.object(adapter.pipeline_runner, 'start', return_value=0) as pipeline, \
             patch.object(adapter.frontend_runner, 'start') as frontend, \
             patch.object(adapter.safety, 'start') as backend:
            self.assertEqual(adapter.run_job(context, opener=object()), 0)
        dependency.assert_not_called()
        private_config.assert_called_once_with(adapter.CONFIG, require_prepared=True)
        fetch.assert_called_once_with(adapter.validate_job_response(context), 'pipeline')
        pipeline.assert_called_once()
        self.assertEqual(pipeline.call_args.args[2], 'spark-other')
        frontend.assert_not_called()
        backend.assert_not_called()

    def test_archive_strips_gitlab_prefix_and_revalidates(self):
        payload = archive([
            ('S15P21E106-deadbeef/', b'', 0o755),
            ('S15P21E106-deadbeef/README.md', b'ignored\n', 0o644),
            ('S15P21E106-deadbeef/backend/gradlew', b'#!/bin/sh\n', 0o755),
            ('S15P21E106-deadbeef/backend/src/A.java', b'class A {}\n', 0o644),
            ('S15P21E106-deadbeef/infra/compose/service.yml', b'services:\n', 0o644),
        ])
        result, digest = adapter.normalized_backend_archive(payload)
        files, _ = adapter.job_io.source_plan(result, digest)
        self.assertEqual(files['backend/src/A.java'][0], b'class A {}\n')
        self.assertEqual(files['infra/compose/service.yml'][0], b'services:\n')
        self.assertTrue(files['backend/gradlew'][1] & 0o100)

    def test_archive_rejects_links_and_multiple_roots(self):
        payload = io.BytesIO()
        with tarfile.open(fileobj=payload, mode='w') as stream:
            link = tarfile.TarInfo('prefix/backend/link'); link.type = tarfile.SYMTYPE
            link.linkname = '/etc/passwd'; stream.addfile(link)
        with self.assertRaises(adapter.PolicyError): adapter.normalized_backend_archive(payload.getvalue())
        with self.assertRaises(adapter.PolicyError): adapter.normalized_backend_archive(archive([
            ('one/backend/a', b'a', 0o600), ('two/backend/b', b'b', 0o600)]))

    def test_authenticated_job_and_mr_must_match(self):
        context = adapter.validate_job_response(response())
        job = {'id': 55, 'name': 'backend-mr-test', 'stage': 'test',
               'source': 'merge_request_event',
               'web_url': 'https://lab.ssafy.com/s15-bigdata-dist-sub1/S15P21E106/-/jobs/55',
               'pipeline': {'id': 77, 'project_id': 99, 'sha': 'a' * 40},
               'commit': {'id': 'a' * 40}}
        mr = [{'state': 'opened', 'target_branch': 'develop', 'source_project_id': 99,
               'target_project_id': 99, 'sha': 'a' * 40}]
        with patch.object(adapter, 'request_json', side_effect=[job, mr]):
            adapter.verify_current_job(object(), context)
            adapter.verify_merge_request(object(), context)
        job.pop('source')
        with patch.object(adapter, 'request_json', return_value=job):
            adapter.verify_current_job(object(), context)

        job['pipeline']['sha'] = 'b' * 40
        job['commit']['id'] = 'c' * 40
        with patch.object(adapter, 'request_json', return_value=job):
            with self.assertRaisesRegex(
                    adapter.PolicyError, r'metadata mismatch: pipeline.sha,commit.id$'):
                adapter.verify_current_job(object(), context)

    def test_cache_prepare_runs_trusted_manager_without_running_source(self):
        context = cache_response()
        mr = {'iid': 98}
        detail = {'iid': 98, 'diff_refs': {'base_sha': 'b' * 40, 'head_sha': 'a' * 40}}
        with patch.object(adapter, 'read_private_config', return_value=({}, Path('/prepared'))), \
             patch.object(adapter, 'verify_current_job', return_value={'pipeline': {'id': 77}}), \
             patch.object(adapter, 'verify_merge_request', return_value=mr), \
             patch.object(adapter, 'fetch_merge_request_detail', return_value=detail) as fetch_detail, \
             patch.object(adapter, 'dependency_changes', return_value=['backend/build.gradle']) as verify, \
             patch.object(adapter, 'prepare_candidate') as prepare, \
             patch.object(adapter, 'fetch_source') as fetch, \
             patch.object(adapter.safety, 'start') as start:
            self.assertEqual(adapter.run_job(context, opener=object()), 0)
            fetch_detail.assert_called_once()
            verify.assert_called_once()
            prepare.assert_called_once()
            fetch.assert_not_called()
            start.assert_not_called()

    def test_cache_prepare_rejects_pipeline_without_dependency_change(self):
        context = cache_response()
        with patch.object(adapter, 'read_private_config', return_value=({}, Path('/prepared'))), \
             patch.object(adapter, 'verify_current_job', return_value={'pipeline': {'id': 77}}), \
             patch.object(adapter, 'verify_merge_request', return_value={'iid': 98}), \
             patch.object(adapter, 'fetch_merge_request_detail', return_value={'iid': 98}), \
             patch.object(adapter, 'dependency_changes', return_value=[]), \
             patch.object(adapter, 'prepare_candidate') as prepare, \
             self.assertRaisesRegex(adapter.PolicyError, 'No approved'):
            adapter.run_job(context, opener=object())
        prepare.assert_not_called()

    def test_cache_status_fetches_and_revalidates_mr_detail(self):
        context = adapter.validate_job_response(cache_response())
        summary = {'iid': 98, 'sha': 'a' * 40}
        detail = {
            'iid': 98, 'state': 'opened', 'target_branch': 'develop',
            'source_project_id': 99, 'target_project_id': 99, 'sha': 'a' * 40,
            'diff_refs': {'base_sha': 'b' * 40, 'start_sha': 'b' * 40,
                          'head_sha': 'a' * 40},
        }
        with patch.object(adapter, 'request_json', return_value=detail) as request:
            self.assertEqual(adapter.fetch_merge_request_detail(object(), context, summary), detail)
        self.assertEqual(request.call_args.args[1],
                         adapter.GITLAB_URL + '/api/v4/projects/99/merge_requests/98')

        invalid = dict(detail, diff_refs=dict(detail['diff_refs'], head_sha='c' * 40))
        with patch.object(adapter, 'request_json', return_value=invalid), \
             self.assertRaisesRegex(adapter.PolicyError, 'diff_refs.head_sha'):
            adapter.fetch_merge_request_detail(object(), context, summary)

    def test_cache_status_diff_must_include_approved_dependency_file(self):
        context = adapter.validate_job_response(cache_response())
        mr = {'iid': 98, 'diff_refs': {'base_sha': 'b' * 40, 'head_sha': 'a' * 40}}
        completed = [SimpleNamespace(stdout=b'') for _ in range(3)]
        completed.append(SimpleNamespace(
            stdout=b'backend/build.gradle\0backend/src/A.java\0'))
        with patch.object(adapter, 'request_json') as api, \
             patch.object(adapter.subprocess, 'run', side_effect=completed) as run:
            self.assertEqual(adapter.dependency_changes(context, mr),
                             ['backend/build.gradle'])
        api.assert_not_called()
        commands = [call.args[0] for call in run.call_args_list]
        self.assertEqual(commands[-1][-2:], ['refs/review/base', 'refs/review/head'])
        self.assertNotIn(context['token'], repr(commands))
        environment = run.call_args_list[1].kwargs['env']
        self.assertEqual(environment['GIT_TERMINAL_PROMPT'], '0')
        self.assertEqual(environment['GIT_CONFIG_KEY_0'], 'http.extraHeader')
        self.assertNotIn(context['token'], repr(environment.keys()))

        unrelated = [SimpleNamespace(stdout=b'') for _ in range(3)]
        unrelated.append(SimpleNamespace(stdout=b'backend/src/A.java\0'))
        with patch.object(adapter.subprocess, 'run', side_effect=unrelated):
            self.assertEqual(adapter.dependency_changes(context, mr), [])

    def test_cache_status_requires_exact_diff_revisions(self):
        context = adapter.validate_job_response(cache_response())
        for mr in (
                {'iid': 98},
                {'iid': 98, 'diff_refs': {'base_sha': 'short', 'head_sha': 'a' * 40}},
                {'iid': 98, 'diff_refs': {'base_sha': 'b' * 40, 'head_sha': 'c' * 40}}):
            with self.subTest(mr=mr), patch.object(adapter.subprocess, 'run') as run, \
                 self.assertRaisesRegex(adapter.PolicyError, 'diff revisions'):
                adapter.dependency_changes(context, mr)
            run.assert_not_called()

    def test_cache_status_git_failure_reports_bounded_stage(self):
        context = adapter.validate_job_response(cache_response())
        mr = {'iid': 98, 'diff_refs': {'base_sha': 'b' * 40, 'head_sha': 'a' * 40}}
        failure = subprocess.CalledProcessError(1, ['/usr/bin/git', 'fetch'])
        cases = (
            ('dependency_git_init', [failure]),
            ('dependency_fetch', [SimpleNamespace(stdout=b''), failure]),
            ('dependency_diff', [SimpleNamespace(stdout=b'') for _ in range(3)] + [failure]),
        )
        for stage, results in cases:
            with self.subTest(stage=stage), \
                 patch.object(adapter.subprocess, 'run', side_effect=results), \
                 self.assertRaises(adapter.AdapterGitError) as raised:
                adapter.dependency_changes(context, mr)
            self.assertEqual(raised.exception.stage, stage)
            self.assertNotIn(context['token'], str(raised.exception))

        stderr = io.StringIO()
        with patch.object(adapter.sys, 'argv', ['ci-gitlab-job-adapter.py', 'run']), \
             patch.object(adapter, 'read_stdin', return_value=cache_response()), \
             patch.object(adapter, 'run_job',
                          side_effect=adapter.AdapterGitError('dependency_fetch')), \
             patch.object(adapter.sys, 'stderr', stderr):
            self.assertEqual(adapter.main(), 70)
        self.assertEqual(stderr.getvalue(),
                         'MR_ADAPTER_GIT_ERROR: stage=dependency_fetch\n')

    def test_cache_status_rejects_unsafe_diff_output(self):
        context = adapter.validate_job_response(cache_response())
        mr = {'iid': 98, 'diff_refs': {'base_sha': 'b' * 40, 'head_sha': 'a' * 40}}
        outputs = (
            b'backend/build.gradle\nmalicious\0',
            b'\xff\0',
            b'\0'.join(('file-' + str(index)).encode() for index in range(100)) + b'\0',
            b'x' * (128 * 1024 + 1),
        )
        for output in outputs:
            completed = [SimpleNamespace(stdout=b'') for _ in range(3)]
            completed.append(SimpleNamespace(stdout=output))
            with self.subTest(output=output[:32]), \
                 patch.object(adapter.subprocess, 'run', side_effect=completed), \
                 self.assertRaises(adapter.PolicyError):
                adapter.dependency_changes(context, mr)

    def test_run_passes_token_only_to_api_and_source_to_safety(self):
        source = archive([('prefix/backend/gradlew', b'x', 0o755)])
        context = response()
        with patch.object(adapter, 'read_private_config', return_value=({}, Path('/prepared'))), \
             patch.object(adapter.runtime, 'require_same'), \
             patch.object(adapter, 'verify_current_job', return_value={'pipeline': {'id': 1}}), \
             patch.object(adapter, 'verify_merge_request', return_value={'iid': 98}), \
             patch.object(adapter, 'fetch_merge_request_detail', return_value={'iid': 98}), \
             patch.object(adapter, 'dependency_changes', return_value=[]), \
             patch.object(adapter, 'fetch_source', return_value=(source, hashlib.sha256(source).hexdigest())), \
             patch.object(adapter.safety, 'start', return_value=0) as start:
            self.assertEqual(adapter.run_job(context, opener=object()), 0)
            args = start.call_args.args
            self.assertEqual(args[:3], (Path('/prepared'), Path('/prepared'), 'backend'))
            self.assertNotIn(context['token'].encode(), args[3])
            self.assertEqual(hashlib.sha256(args[3]).hexdigest(), args[4])

    def test_dependency_mr_test_selects_only_its_verified_candidate(self):
        source = archive([('prefix/backend/gradlew', b'x', 0o755)])
        context = response()
        selected = Path('/candidate-for-a')
        with patch.object(adapter, 'read_private_config', return_value=({}, Path('/default'))), \
             patch.object(adapter.runtime, 'require_same'), \
             patch.object(adapter, 'verify_current_job', return_value={'pipeline': {'id': 1}}), \
             patch.object(adapter, 'verify_merge_request', return_value={'iid': 98}), \
             patch.object(adapter, 'fetch_merge_request_detail', return_value={'iid': 98}), \
             patch.object(adapter, 'dependency_changes', return_value=['backend/build.gradle']), \
             patch.object(adapter, 'verified_candidate', return_value=selected) as candidate, \
             patch.object(adapter, 'fetch_source', return_value=(source, hashlib.sha256(source).hexdigest())), \
             patch.object(adapter.safety, 'start', return_value=0) as start:
            self.assertEqual(adapter.run_job(context, opener=object()), 0)
        candidate.assert_called_once_with(adapter.validate_job_response(context), 98)
        self.assertEqual(start.call_args.args[:3], (selected, selected, 'backend'))

    def test_http_error_reports_only_stage_and_status(self):
        context = response()
        error = urllib.error.HTTPError('https://secret.invalid/path', 403, 'forbidden', {}, None)
        with patch.object(adapter, 'read_private_config', return_value=({}, Path('/prepared'))), \
             patch.object(adapter.runtime, 'require_same'), \
             patch.object(adapter, 'verify_current_job'), \
             patch.object(adapter, 'verify_merge_request', side_effect=error):
            with self.assertRaises(adapter.AdapterHttpError) as raised:
                adapter.run_job(context, opener=object())
        self.assertEqual((raised.exception.stage, raised.exception.status), ('merge_request', 403))
        self.assertNotIn('secret.invalid', str(raised.exception))

    def test_runtime_mismatch_is_rejected_before_gitlab_or_workload(self):
        context = response()
        with patch.object(adapter, 'read_private_config', return_value=({}, Path('/prepared'))), \
             patch.object(adapter.runtime, 'require_same', side_effect=RuntimeError(
                 'Installed and prepared backend runtimes differ')) as compatible, \
             patch.object(adapter, 'verify_current_job') as current, \
             patch.object(adapter.safety, 'start') as start, \
             self.assertRaisesRegex(RuntimeError, 'backend runtimes differ'):
            adapter.run_job(context, opener=object())
        compatible.assert_called_once_with(adapter.HERE, Path('/prepared/scripts'))
        current.assert_not_called()
        start.assert_not_called()

    def test_owner_stop_accepts_only_owned_unit_name(self):
        with patch.object(adapter.subprocess, 'run') as run:
            run.return_value.returncode = 0
            adapter.stop_owner_unit('ci222-owner-012345abcdef.service')
            self.assertEqual(run.call_args.args[0],
                             ['systemctl', 'stop', 'ci222-owner-012345abcdef.service'])
            adapter.stop_owner_unit('ci352-owner-012345abcdef.service')
            self.assertEqual(run.call_args.args[0],
                             ['systemctl', 'stop', 'ci352-owner-012345abcdef.service'])
            adapter.stop_owner_unit('ci486-owner-012345abcdef.service')
            self.assertEqual(run.call_args.args[0],
                             ['systemctl', 'stop', 'ci486-owner-012345abcdef.service'])
        with self.assertRaises(ValueError):
            adapter.stop_owner_unit('ci222-server-012345abcdef.service')


if __name__ == '__main__': unittest.main()
