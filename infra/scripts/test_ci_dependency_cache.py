import io
import importlib.util
import json
import os
from pathlib import Path
import stat
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent


def loaded(name):
    spec = importlib.util.spec_from_file_location(name, HERE / (name.replace('_', '-') + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


context_module = loaded('ci_dependency_cache_context')
cache = loaded('ci_dependency_cache')


class ContextTests(unittest.TestCase):
    def environment(self, **changes):
        value = {
            'CI_PIPELINE_SOURCE': 'web', 'CI_COMMIT_BRANCH': 'develop',
            'CI_COMMIT_REF_PROTECTED': 'true',
            'CI_PROJECT_PATH': 's15-bigdata-dist-sub1/S15P21E106',
            'CACHE_ACTION': 'prepare', 'CACHE_MR_IID': '98',
            'CACHE_COMMIT_SHA': 'a' * 40, 'CI_JOB_TOKEN': 'x' * 24,
            'CI_PROJECT_ID': '1441379', 'CI_PIPELINE_ID': '123', 'CI_JOB_ID': '456',
        }
        value.update(changes)
        return value

    def test_context_accepts_only_protected_develop_web_pipeline(self):
        result = context_module.context(self.environment())
        self.assertEqual((result['mr_iid'], result['revision']), (98, 'a' * 40))
        for name, value in (
                ('CI_PIPELINE_SOURCE', 'merge_request_event'),
                ('CI_COMMIT_BRANCH', 'feature'),
                ('CI_COMMIT_REF_PROTECTED', 'false')):
            with self.subTest(name=name), self.assertRaises(ValueError):
                context_module.context(self.environment(**{name: value}))

    def test_context_rejects_bad_target_and_token(self):
        with self.assertRaises(ValueError):
            context_module.context(self.environment(CACHE_COMMIT_SHA='main'))
        with self.assertRaises(ValueError):
            context_module.context(self.environment(CI_JOB_TOKEN='short'))
        with self.assertRaises(ValueError):
            context_module.context(self.environment(CI_PROJECT_ID='0'))


class ManagerTests(unittest.TestCase):
    def context(self, action='prepare'):
        return {'action': action, 'project_id': 1441379, 'pipeline_id': 1,
                'job_id': 2, 'mr_iid': 98,
                'revision': 'a' * 40, 'token': 'x' * 24}

    def test_stdin_context_is_exact_and_bounded(self):
        raw = json.dumps(self.context()).encode()
        self.assertEqual(cache.read_context(io.BytesIO(raw))['mr_iid'], 98)
        changed = self.context(); changed['extra'] = True
        with self.assertRaises(cache.PolicyError):
            cache.read_context(io.BytesIO(json.dumps(changed).encode()))

    def test_mr_requires_exact_open_head_and_dependency_change(self):
        mr = {'state': 'opened', 'target_branch': 'develop', 'source_project_id': 7,
              'target_project_id': 7, 'sha': 'a' * 40}
        diffs = [{'old_path': 'backend/build.gradle', 'new_path': 'backend/build.gradle',
                  'diff': "+ implementation 'org.springframework.kafka:spring-kafka'\n"}]
        with patch.object(cache, 'api_json', side_effect=[mr, diffs]):
            _, changes, declarations = cache.verify_mr(self.context())
        self.assertEqual(changes, ['backend/build.gradle'])
        self.assertEqual(declarations, ["+ implementation 'org.springframework.kafka:spring-kafka'"])
        with patch.object(cache, 'api_json', side_effect=[mr, [
                {'old_path': 'backend/src/A.java', 'new_path': 'backend/src/A.java'}]]):
            with self.assertRaises(cache.PolicyError):
                cache.verify_mr(self.context())

    def test_current_job_and_root_operator_policy_are_verified(self):
        job = {
            'id': 2, 'name': 'backend-cache-prepare', 'stage': 'verify',
            'status': 'running', 'ref': 'develop', 'source': 'web',
            'pipeline': {'id': 1, 'project_id': 1441379, 'ref': 'develop',
                         'sha': 'b' * 40},
            'commit': {'id': 'b' * 40},
            'user': {'id': 7, 'username': 'infra-user', 'state': 'active'},
        }
        with tempfile.TemporaryDirectory() as directory:
            operators = Path(directory) / 'cache-operators.json'
            operators.write_text(json.dumps({'usernames': ['infra-user']}))
            operators.chmod(0o600)
            trusted = SimpleNamespace(st_mode=stat.S_IFREG | 0o600, st_uid=0)
            with patch.object(cache, 'OPERATORS', operators), \
                 patch.object(Path, 'lstat', return_value=trusted), \
                 patch.object(cache, 'api_json', return_value=job) as api:
                self.assertEqual(cache.verify_job(self.context()), 'infra-user')
            api.assert_called_once_with('job', 'x' * 24, None)
            operators.write_text(json.dumps({'usernames': ['someone-else']}))
            with patch.object(cache, 'OPERATORS', operators), \
                 patch.object(Path, 'lstat', return_value=trusted), \
                 patch.object(cache, 'api_json', return_value=job), \
                 self.assertRaisesRegex(cache.PolicyError, 'cache operator'):
                cache.verify_job(self.context())

    def test_current_job_identity_must_match_requested_action(self):
        job = {
            'id': 2, 'name': 'backend-cache-promote', 'stage': 'verify',
            'status': 'running', 'ref': 'develop', 'source': 'web',
            'pipeline': {'id': 1, 'project_id': 1441379, 'ref': 'develop',
                         'sha': 'b' * 40},
            'commit': {'id': 'b' * 40},
            'user': {'id': 7, 'username': 'infra-user', 'state': 'active'},
        }
        with patch.object(cache, 'api_json', return_value=job), \
             self.assertRaisesRegex(cache.PolicyError, 'job_name'):
            cache.verify_job(self.context('prepare'))
        wrong_project = self.context('promote')
        wrong_project['project_id'] = 1
        with patch.object(cache, 'api_json', return_value=job), \
             self.assertRaisesRegex(cache.PolicyError, 'project'):
            cache.verify_job(wrong_project)

    def test_switch_restores_config_when_runner_restart_fails(self):
        old = {'gitlab_url': cache.GITLAB_URL, 'project_path': cache.PROJECT_PATH,
               'prepared_base': '/var/tmp/ci222-server-old'}
        writes = []
        with patch.object(cache, 'active_ci_units', return_value=[]), \
             patch.object(cache, 'atomic_config', side_effect=lambda value: writes.append(value.copy())), \
             patch.object(cache.subprocess, 'run') as run:
            run.side_effect = [object(), RuntimeError('restart failed'), object()]
            with self.assertRaises(RuntimeError):
                cache.switch_stage(old, '/var/tmp/ci222-server-new')
        self.assertEqual(writes, [dict(old, prepared_base='/var/tmp/ci222-server-new'), old])

    def test_switch_refuses_active_mr_test_units(self):
        with patch.object(cache, 'active_ci_units', return_value=['ci222-owner-a.service']):
            with self.assertRaises(cache.PolicyError):
                cache.switch_stage({}, '/var/tmp/ci222-server-new')

    def test_source_contains_no_shell_or_unbounded_delete(self):
        source = (HERE / 'ci-dependency-cache.py').read_text()
        self.assertNotIn('shell=True', source)
        self.assertNotIn('rmtree(', source)
        self.assertIn("fcntl.LOCK_EX | fcntl.LOCK_NB", source)
        self.assertIn("run / 'lifecycle.json'", source)

    def test_gradle_init_script_restricts_dependency_repositories(self):
        source = (HERE / 'rootless-backend/pilot.init.gradle').read_text()
        self.assertIn('https://repo.maven.apache.org/maven2/', source)
        self.assertIn('https://plugins.gradle.org/m2/', source)
        self.assertIn('settings.pluginManagement.repositories.whenObjectAdded', source)
        self.assertIn('project.buildscript.repositories.whenObjectAdded', source)
        self.assertNotIn('mavenLocal()', source)


if __name__ == '__main__':
    unittest.main()
