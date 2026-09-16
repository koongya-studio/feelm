from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
BACKEND_CI = ROOT / '.gitlab' / 'ci' / 'backend.yml'


def job_block(text, name):
    match = re.search(
        r'(?ms)^' + re.escape(name) + r':\n(.*?)(?=^[.]?[A-Za-z0-9_-]+:\n|\Z)',
        text,
    )
    if match is None:
        raise AssertionError('Missing CI job: ' + name)
    return match.group(1)


def change_patterns(block):
    match = re.search(r'(?m)^      changes:\n((?:        - .+\n)+)', block)
    if match is None:
        raise AssertionError('Missing changes rule')
    return [line.removeprefix('        - ') for line in match.group(1).splitlines()]


def glob_regex(pattern):
    index = 0
    output = '^'
    while index < len(pattern):
        if pattern.startswith('**/*', index):
            output += '(?:.*/)?[^/]*'
            index += 4
        elif pattern.startswith('**', index):
            output += '.*'
            index += 2
        elif pattern[index] == '*':
            output += '[^/]*'
            index += 1
        elif pattern[index] == '?':
            output += '[^/]'
            index += 1
        else:
            output += re.escape(pattern[index])
            index += 1
    return re.compile(output + '$')


def matches(path, patterns):
    return any(glob_regex(pattern).fullmatch(path) for pattern in patterns)


def should_run(paths, patterns):
    return any(matches(path, patterns) for path in paths)


class BackendDevelopPathRulesTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ci = BACKEND_CI.read_text()
        cls.blocks = {
            name: job_block(cls.ci, name)
            for name in ('datastore-config-check', 'backend-env-check', 'backend-cache-promote',
                         'backend-test', 'backend-image', 'deploy-backend', 'deploy-redis')
        }
        cls.patterns = {
            name: change_patterns(block) for name, block in cls.blocks.items()
        }

    def test_unrelated_frontend_pipeline_and_document_changes_are_excluded(self):
        unrelated = (
            'frontend/src/main.tsx',
            'pipeline/src/consumer.py',
            'docs/README.md',
            'backend/README.md',
            'backend/docs/operations.md',
            'infra/verification/S15P21E106-404.md',
        )
        for job in ('backend-env-check', 'backend-test', 'backend-image', 'deploy-backend'):
            with self.subTest(job=job):
                self.assertFalse(should_run(unrelated, self.patterns[job]))

    def test_backend_runtime_build_and_container_inputs_are_included(self):
        inputs = (
            'backend/src/main/java/com/feelm/backend/BackendApplication.java',
            'backend/src/main/resources/application.yml',
            'backend/src/test/java/com/feelm/backend/BackendApplicationTests.java',
            'backend/src/test/resources/application.yml',
            'backend/gradle/test-timing.gradle',
            'backend/gradle/wrapper/gradle-wrapper.jar',
            'backend/build.gradle',
            'backend/build.gradle.kts',
            'backend/settings.gradle',
            'backend/settings.gradle.kts',
            'backend/gradle.properties',
            'backend/gradlew',
            'backend/gradlew.bat',
            'backend/Dockerfile',
            'backend/Dockerfile.dev',
            'backend/.dockerignore',
            'backend/.env.example',
            'backend/config/deployment-environment.properties',
        )
        for job in ('backend-env-check', 'backend-test', 'backend-image', 'deploy-backend'):
            for path in inputs:
                with self.subTest(job=job, path=path):
                    self.assertTrue(matches(path, self.patterns[job]))

    def test_deployment_and_datastore_inputs_keep_the_chain_aligned(self):
        inputs = (
            'infra/compose/service.yml',
            'infra/scripts/verify-datastore-config.sh',
            'infra/scripts/verify-datastores.sh',
            'infra/scripts/deploy-backend.sh',
            'infra/scripts/rollback-backend.sh',
        )
        for path in inputs:
            for job in ('backend-env-check', 'backend-test', 'backend-image', 'deploy-backend'):
                with self.subTest(job=job, path=path):
                    self.assertTrue(matches(path, self.patterns[job]))
        for path in inputs:
            with self.subTest(job='datastore-config-check', path=path):
                self.assertTrue(matches(path, self.patterns['datastore-config-check']))

    def test_redis_config_changes_use_the_selective_deployment(self):
        redis_inputs = (
            'infra/redis/start.sh',
            'infra/redis/readonly.acl',
            'infra/scripts/deploy-redis.sh',
        )
        for path in redis_inputs:
            with self.subTest(path=path):
                self.assertTrue(matches(path, self.patterns['datastore-config-check']))
                self.assertTrue(matches(path, self.patterns['deploy-redis']))
                for job in ('backend-env-check', 'backend-test', 'backend-image', 'deploy-backend'):
                    self.assertFalse(matches(path, self.patterns[job]))
        self.assertTrue(
            matches('infra/scripts/test_deploy_redis.py', self.patterns['datastore-config-check'])
        )
        self.assertFalse(
            matches('infra/scripts/test_deploy_redis.py', self.patterns['deploy-redis'])
        )

    def test_mixed_delete_and_rename_scenarios_use_all_changed_paths(self):
        scenarios = (
            (('backend/src/main/resources/legacy.yml',), True),
            (('backend/README.md', 'backend/docs/README.md'), False),
            (('backend/src/main/resources/legacy.yml', 'backend/docs/legacy.md'), True),
            (('frontend/README.md', 'backend/src/main/resources/application.yml'), True),
        )
        for job in ('backend-env-check', 'backend-test', 'backend-image', 'deploy-backend'):
            for paths, expected in scenarios:
                with self.subTest(job=job, paths=paths):
                    self.assertEqual(should_run(paths, self.patterns[job]), expected)

    def test_runtime_patterns_match_across_the_develop_chain(self):
        prefixes = ('backend/', 'infra/compose/', 'infra/redis/')
        expected = {
            pattern for pattern in self.patterns['backend-test']
            if pattern.startswith(prefixes)
        }
        for job in ('backend-env-check', 'backend-image', 'deploy-backend'):
            actual = {
                pattern for pattern in self.patterns[job]
                if pattern.startswith(prefixes)
            }
            self.assertEqual(actual, expected, job)

    def test_dependency_changes_keep_cache_promotion_before_the_chain(self):
        cache_patterns = self.patterns['backend-cache-promote']
        for pattern in cache_patterns:
            representative = pattern.replace('*', 'example')
            for job in ('backend-env-check', 'backend-test', 'backend-image', 'deploy-backend'):
                with self.subTest(job=job, pattern=pattern):
                    self.assertTrue(matches(representative, self.patterns[job]))
        cache_action = job_block(self.ci, '.backend-cache-action')
        self.assertIn('extends: .backend-cache-action', self.blocks['backend-cache-promote'])
        self.assertIn('stage: verify', cache_action)
        self.assertIn('resource_group: service-and-backend-cache', cache_action)

    def test_needs_rules_and_dedicated_validation_are_preserved(self):
        self.assertIn('job: backend-test', self.blocks['backend-image'])
        self.assertIn('job: backend-env-check', self.blocks['backend-image'])
        self.assertIn('job: datastore-config-check', self.blocks['backend-image'])
        self.assertIn('optional: true', self.blocks['backend-image'])
        self.assertIn('needs: [backend-image]', self.blocks['deploy-backend'])
        self.assertIn('$CI_PIPELINE_SOURCE == "push"', self.blocks['deploy-backend'])
        self.assertIn('$CI_COMMIT_REF_PROTECTED == "true"', self.blocks['deploy-backend'])
        self.assertIn('infra.scripts.test_ci_config', self.blocks['backend-test'])
        self.assertIn('infra.scripts.test_ci_backend_mr_paths', self.blocks['backend-test'])
        self.assertIn('infra.scripts.test_ci_backend_develop_paths', self.blocks['backend-test'])
        self.assertIn('infra.scripts.test_ci_backend_env_contract', self.blocks['backend-test'])
        self.assertIn('job: datastore-config-check', self.blocks['deploy-redis'])
        self.assertIn('resource_group: service-and-backend-cache', self.blocks['deploy-redis'])
        self.assertIn('$CI_PIPELINE_SOURCE == "push"', self.blocks['deploy-redis'])
        self.assertIn('$CI_COMMIT_REF_PROTECTED == "true"', self.blocks['deploy-redis'])
        self.assertIn('bash infra/scripts/deploy-redis.sh "$CI_COMMIT_SHA"', self.blocks['deploy-redis'])
        self.assertIn('infra.scripts.test_deploy_redis', self.blocks['backend-test'])


if __name__ == '__main__':
    unittest.main()
