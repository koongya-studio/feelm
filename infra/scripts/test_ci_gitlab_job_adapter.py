import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
import urllib.error
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('gitlab_adapter', HERE / 'ci-gitlab-job-adapter.py')
adapter = importlib.util.module_from_spec(spec); spec.loader.exec_module(adapter)


def response(script=None):
    return {
        'id': 55, 'token': 'valid-job-token-123456789',
        'job_info': {'name': 'backend-mr-test', 'stage': 'test', 'project_id': 99},
        'git_info': {'repo_url': 'https://gitlab-ci-token:hidden@lab.ssafy.com/'
                     's15-bigdata-dist-sub1/S15P21E106.git',
                     'sha': 'a' * 40, 'ref_type': 'branch'},
        'steps': [{'name': 'script', 'script': script or adapter.EXPECTED_SCRIPT}],
        'services': [], 'artifacts': [], 'cache': [], 'dependencies': [], 'image': {'name': ''},
    }


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
    def test_context_accepts_only_fixed_job_and_script(self):
        result = adapter.validate_job_response(response())
        self.assertEqual(result['project_id'], 99)
        with self.assertRaises(adapter.PolicyError):
            adapter.validate_job_response(response(['curl http://metadata/']))
        changed = response(); changed['services'] = [{'name': 'docker'}]
        with self.assertRaises(adapter.PolicyError): adapter.validate_job_response(changed)

    def test_archive_strips_gitlab_prefix_and_revalidates(self):
        payload = archive([
            ('S15P21E106-deadbeef/', b'', 0o755),
            ('S15P21E106-deadbeef/README.md', b'ignored\n', 0o644),
            ('S15P21E106-deadbeef/backend/gradlew', b'#!/bin/sh\n', 0o755),
            ('S15P21E106-deadbeef/backend/src/A.java', b'class A {}\n', 0o644),
        ])
        result, digest = adapter.normalized_backend_archive(payload)
        files, _ = adapter.job_io.source_plan(result, digest)
        self.assertEqual(files['backend/src/A.java'][0], b'class A {}\n')
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
               'pipeline': {'project_id': 99, 'sha': 'a' * 40}, 'commit': {'id': 'a' * 40}}
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

    def test_run_passes_token_only_to_api_and_source_to_safety(self):
        source = archive([('prefix/backend/gradlew', b'x', 0o755)])
        context = response()
        with patch.object(adapter, 'read_private_config', return_value=({}, Path('/prepared'))), \
             patch.object(adapter, 'verify_current_job'), patch.object(adapter, 'verify_merge_request'), \
             patch.object(adapter, 'fetch_source', return_value=(source, hashlib.sha256(source).hexdigest())), \
             patch.object(adapter.safety, 'start', return_value=0) as start:
            self.assertEqual(adapter.run_job(context, opener=object()), 0)
            args = start.call_args.args
            self.assertEqual(args[:3], (Path('/prepared'), Path('/prepared'), 'backend'))
            self.assertNotIn(context['token'].encode(), args[3])
            self.assertEqual(hashlib.sha256(args[3]).hexdigest(), args[4])

    def test_http_error_reports_only_stage_and_status(self):
        context = response()
        error = urllib.error.HTTPError('https://secret.invalid/path', 403, 'forbidden', {}, None)
        with patch.object(adapter, 'read_private_config', return_value=({}, Path('/prepared'))), \
             patch.object(adapter, 'verify_current_job'), \
             patch.object(adapter, 'verify_merge_request', side_effect=error):
            with self.assertRaises(adapter.AdapterHttpError) as raised:
                adapter.run_job(context, opener=object())
        self.assertEqual((raised.exception.stage, raised.exception.status), ('merge_request', 403))
        self.assertNotIn('secret.invalid', str(raised.exception))

    def test_owner_stop_accepts_only_owned_unit_name(self):
        with patch.object(adapter.subprocess, 'run') as run:
            run.return_value.returncode = 0
            adapter.stop_owner_unit('ci222-owner-012345abcdef.service')
            self.assertEqual(run.call_args.args[0],
                             ['systemctl', 'stop', 'ci222-owner-012345abcdef.service'])
        with self.assertRaises(ValueError):
            adapter.stop_owner_unit('ci222-server-012345abcdef.service')


if __name__ == '__main__': unittest.main()
