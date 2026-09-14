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
            'CACHE_ACTION': 'rollback', 'CACHE_MR_IID': '98',
            'CACHE_COMMIT_SHA': 'a' * 40, 'CI_JOB_TOKEN': 'x' * 24,
            'CI_COMMIT_SHA': 'b' * 40,
            'CI_PROJECT_ID': '1441379', 'CI_PIPELINE_ID': '123', 'CI_JOB_ID': '456',
        }
        value.update(changes)
        return value

    def test_rollback_context_accepts_only_protected_develop_web_pipeline(self):
        result = context_module.context(self.environment())
        self.assertEqual((result['mr_iid'], result['revision']), (98, 'a' * 40))
        for name, value in (
                ('CI_PIPELINE_SOURCE', 'merge_request_event'),
                ('CI_COMMIT_BRANCH', 'feature'),
                ('CI_COMMIT_REF_PROTECTED', 'false')):
            with self.subTest(name=name), self.assertRaises(ValueError):
                context_module.context(self.environment(**{name: value}))

    def test_promote_context_is_derived_from_protected_develop_push(self):
        result = context_module.context(self.environment(
            CACHE_ACTION='promote', CI_PIPELINE_SOURCE='push'))
        self.assertIsNone(result['mr_iid'])
        self.assertEqual(result['revision'], 'b' * 40)
        with self.assertRaises(ValueError):
            context_module.context(self.environment(
                CACHE_ACTION='promote', CI_PIPELINE_SOURCE='web'))

    def test_context_rejects_bad_target_and_token(self):
        with self.assertRaises(ValueError):
            context_module.context(self.environment(CACHE_COMMIT_SHA='main'))
        with self.assertRaises(ValueError):
            context_module.context(self.environment(CI_JOB_TOKEN='short'))
        with self.assertRaises(ValueError):
            context_module.context(self.environment(CI_PROJECT_ID='0'))
        with self.assertRaises(ValueError):
            context_module.context(self.environment(CACHE_ACTION='prepare'))


class ManagerTests(unittest.TestCase):
    def context(self, action='rollback'):
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
              'target_project_id': 7, 'sha': 'a' * 40,
              'diff_refs': {'base_sha': 'b' * 40, 'start_sha': 'b' * 40,
                            'head_sha': 'a' * 40}}
        with patch.object(cache, 'api_json', return_value=mr):
            self.assertEqual(cache.verify_mr(self.context('prepare')), mr)
        invalid = dict(mr, diff_refs=dict(mr['diff_refs'], head_sha='c' * 40))
        with patch.object(cache, 'api_json', return_value=invalid):
            with self.assertRaises(cache.PolicyError):
                cache.verify_mr(self.context('prepare'))

    def test_dependency_review_uses_git_diff_instead_of_mr_diffs_api(self):
        mr = {'diff_refs': {'base_sha': 'b' * 40}}
        names = b'backend/build.gradle\0backend/src/A.java\0'
        diff = b"+ implementation 'org.springframework.kafka:spring-kafka'\n"
        completed = [SimpleNamespace(stdout=b''), SimpleNamespace(stdout=b''),
                     SimpleNamespace(stdout=b''), SimpleNamespace(stdout=names),
                     SimpleNamespace(stdout=diff)]
        with patch.object(cache.subprocess, 'run', side_effect=completed):
            changes, declarations = cache.review_dependency_changes(self.context('prepare'), mr)
        self.assertEqual(changes, ['backend/build.gradle'])
        self.assertEqual(declarations, ["+ implementation 'org.springframework.kafka:spring-kafka'"])

    def test_current_job_and_root_operator_policy_are_verified(self):
        job = {
            'id': 2, 'name': 'backend-cache-rollback', 'stage': 'verify',
            'status': 'running', 'ref': 'develop',
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
            'status': 'running', 'ref': 'develop',
            'pipeline': {'id': 1, 'project_id': 1441379, 'ref': 'develop',
                         'sha': 'b' * 40},
            'commit': {'id': 'b' * 40},
            'user': {'id': 7, 'username': 'infra-user', 'state': 'active'},
        }
        wrong_project = self.context('promote')
        wrong_project['project_id'] = 1
        wrong_project['revision'] = 'b' * 40
        with patch.object(cache, 'api_json', return_value=job), \
             self.assertRaisesRegex(cache.PolicyError, 'project'):
            cache.verify_job(wrong_project)

    def test_automatic_promote_does_not_require_operator_allowlist(self):
        job = {
            'id': 2, 'name': 'backend-cache-promote', 'stage': 'verify',
            'status': 'running', 'ref': 'develop',
            'pipeline': {'id': 1, 'project_id': 1441379, 'ref': 'develop',
                         'sha': 'a' * 40},
            'commit': {'id': 'a' * 40},
            'user': {'id': 7, 'username': 'any-active-user', 'state': 'active'},
        }
        context = self.context('promote'); context['mr_iid'] = None
        with patch.object(cache, 'api_json', return_value=job), \
             patch.object(cache, 'operator_usernames') as operators:
            self.assertEqual(cache.verify_job(context), 'automatic-develop-pipeline')
        operators.assert_not_called()

    def test_merged_develop_commit_resolves_one_exact_candidate_revision(self):
        context = self.context('promote'); context['mr_iid'] = None
        summary = {'iid': 163}
        detail = {
            'iid': 163, 'state': 'merged', 'target_branch': 'develop',
            'source_project_id': 1441379, 'target_project_id': 1441379,
            'sha': 'c' * 40, 'merge_commit_sha': 'a' * 40,
            'squash_commit_sha': 'd' * 40,
            'diff_refs': {'head_sha': 'c' * 40},
        }
        with patch.object(cache, 'api_json', side_effect=[[summary], detail]):
            self.assertEqual(cache.merged_mr(context), detail)
        with patch.object(cache, 'api_json', side_effect=[[summary],
                                                          dict(detail, state='opened')]), \
             self.assertRaises(cache.PolicyError):
            cache.merged_mr(context)

    def test_verified_candidate_is_revision_and_mr_scoped(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / 'state'
            stage = Path(directory) / 'stage'; stage.mkdir()
            (stage / 'prepared.json').write_text(json.dumps({
                'source_revision': 'a' * 40, 'cache_manifest_sha256': 'manifest'}))
            candidate = state / 'candidates' / ('a' * 40 + '.json')
            candidate.parent.mkdir(parents=True)
            candidate.write_text(json.dumps({
                'state': 'VERIFIED', 'mr_iid': 98, 'revision': 'a' * 40,
                'manifest_sha256': 'manifest', 'stage': str(stage)}))
            candidate.chmod(0o600)
            trusted = SimpleNamespace(st_mode=stat.S_IFREG | 0o600, st_uid=0)
            pilot = SimpleNamespace(check_base=lambda value: self.assertEqual(value, stage))
            with patch.object(cache, 'STATE', state), \
                 patch.object(Path, 'lstat', return_value=trusted), \
                 patch.object(cache, 'load', return_value=pilot):
                self.assertEqual(cache.prepared_candidate('a' * 40, 98), stage)
                with self.assertRaises(cache.PolicyError):
                    cache.prepared_candidate('a' * 40, 99)

    def test_automatic_prepare_requires_root_fixed_context_and_serializes(self):
        context = self.context('prepare')
        fake_fcntl = SimpleNamespace(LOCK_EX=1, LOCK_NB=2, flock=lambda *_: None)
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(cache, 'LOCK', Path(directory) / 'cache.lock'), \
             patch.object(cache, 'fcntl', fake_fcntl), \
             patch.object(cache.os, 'geteuid', return_value=0, create=True), \
             patch.object(cache, 'prepare') as prepare:
            cache.automatic_prepare(context)
        prepare.assert_called_once_with(context)
        with self.assertRaises(cache.PolicyError):
            cache.automatic_prepare(dict(context, project_id=1))

    def test_prepare_retry_reuses_verified_candidate(self):
        context = self.context('prepare')
        with patch.object(cache, 'candidate_file') as candidate_file, \
             patch.object(cache, 'prepared_candidate') as prepared, \
             patch.object(cache, 'verify_mr') as verify:
            candidate_file.return_value.exists.return_value = True
            cache.prepare(context)
        prepared.assert_called_once_with('a' * 40, 98)
        verify.assert_not_called()

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

    def test_promote_records_merge_revision_only_after_switch(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stage = root / 'stage'; stage.mkdir()
            (stage / 'prepared.json').write_text(json.dumps({
                'source_revision': 'c' * 40, 'cache_manifest_sha256': 'manifest'}))
            record = root / 'candidate.json'; record.write_text('{}')
            adapter = root / 'adapter.json'; adapter.write_text(json.dumps({
                'gitlab_url': cache.GITLAB_URL, 'project_path': cache.PROJECT_PATH,
                'prepared_base': '/previous'}))
            candidate = {'state': 'VERIFIED', 'mr_iid': 163, 'revision': 'c' * 40,
                         'manifest_sha256': 'manifest', 'stage': str(stage)}
            context = self.context('promote'); context['mr_iid'] = None
            pilot = SimpleNamespace(check_base=lambda value: self.assertEqual(value, stage))
            write = lambda path, data: path.write_bytes(data)
            with patch.object(cache, 'ADAPTER_CONFIG', adapter), \
                 patch.object(cache, 'merged_mr', return_value={'iid': 163, 'sha': 'c' * 40}), \
                 patch.object(cache, 'read_candidate', return_value=(record, candidate)), \
                 patch.object(cache, 'load', return_value=pilot), \
                 patch.object(cache, 'write_private', side_effect=write), \
                 patch.object(cache, 'switch_stage') as switch:
                cache.promote(context)
            switch.assert_called_once()
            saved = json.loads(record.read_text())
            self.assertEqual(saved['state'], 'PROMOTED')
            self.assertEqual(saved['merged_revision'], 'a' * 40)

    def test_promote_record_failure_restores_previous_default(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stage = root / 'stage'; stage.mkdir()
            (stage / 'prepared.json').write_text(json.dumps({
                'source_revision': 'c' * 40, 'cache_manifest_sha256': 'manifest'}))
            adapter = root / 'adapter.json'; adapter.write_text(json.dumps({
                'gitlab_url': cache.GITLAB_URL, 'project_path': cache.PROJECT_PATH,
                'prepared_base': '/previous'}))
            candidate = {'state': 'VERIFIED', 'mr_iid': 163, 'revision': 'c' * 40,
                         'manifest_sha256': 'manifest', 'stage': str(stage)}
            context = self.context('promote'); context['mr_iid'] = None
            pilot = SimpleNamespace(check_base=lambda _value: None)
            with patch.object(cache, 'ADAPTER_CONFIG', adapter), \
                 patch.object(cache, 'merged_mr', return_value={'iid': 163, 'sha': 'c' * 40}), \
                 patch.object(cache, 'read_candidate',
                              return_value=(root / 'candidate.json', candidate)), \
                 patch.object(cache, 'load', return_value=pilot), \
                 patch.object(cache, 'write_private', side_effect=OSError('disk full')), \
                 patch.object(cache, 'switch_stage') as switch, \
                 self.assertRaises(OSError):
                cache.promote(context)
        self.assertEqual(switch.call_count, 2)
        self.assertEqual(switch.call_args_list[0].args[1], str(stage))
        self.assertEqual(switch.call_args_list[1].args[1], '/previous')

    def test_rollback_keeps_newer_active_stage_unchanged(self):
        context = self.context('rollback')
        candidate = {'state': 'PROMOTED', 'mr_iid': 98, 'stage': '/candidate',
                     'previous_stage': '/previous'}
        with patch.object(cache, 'read_candidate', return_value=(Path('/record'), candidate)), \
             patch.object(cache, 'ADAPTER_CONFIG') as config, \
             patch.object(cache, 'switch_stage') as switch:
            config.read_text.return_value = json.dumps({'prepared_base': '/newer'})
            with self.assertRaises(cache.PolicyError):
                cache.rollback(context)
        switch.assert_not_called()

    def test_source_contains_no_shell_or_unbounded_delete(self):
        source = (HERE / 'ci-dependency-cache.py').read_text()
        self.assertNotIn('shell=True', source)
        self.assertNotIn('rmtree(', source)
        self.assertIn("fcntl.LOCK_EX | fcntl.LOCK_NB", source)
        self.assertIn("run / 'lifecycle.json'", source)

    def test_ci_serializes_prepare_test_promotion_and_deploy(self):
        config = (HERE.parent.parent / '.gitlab/ci/backend.yml').read_text()
        for job in ('backend-cache-prepare', 'backend-mr-test', 'deploy-backend'):
            block = config.split('\n' + job + ':', 1)[1].split('\n\n', 1)[0]
            self.assertIn('resource_group: service-and-backend-cache', block)
        template = config.split('\n.backend-cache-action:', 1)[1].split('\n\n', 1)[0]
        self.assertIn('resource_group: service-and-backend-cache', template)
        for job in ('backend-cache-promote', 'backend-cache-rollback'):
            block = config.split('\n' + job + ':', 1)[1].split('\n\n', 1)[0]
            self.assertIn('extends: .backend-cache-action', block)
        self.assertIn("$CI_PIPELINE_SOURCE == \"push\"", config)

    def test_gradle_init_script_restricts_dependency_repositories(self):
        source = (HERE / 'rootless-backend/pilot.init.gradle').read_text()
        self.assertIn('https://repo.maven.apache.org/maven2/', source)
        self.assertIn('https://plugins.gradle.org/m2/', source)
        self.assertIn('settings.pluginManagement.repositories.whenObjectAdded', source)
        self.assertIn('project.buildscript.repositories.whenObjectAdded', source)
        self.assertIn("apply from: new File(gradle.startParameter.currentDir, 'gradle/test-timing.gradle')", source)
        self.assertNotIn('mavenLocal()', source)


if __name__ == '__main__':
    unittest.main()
