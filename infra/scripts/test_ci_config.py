from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
CI_ROOT = ROOT / '.gitlab-ci.yml'
CI_DIR = ROOT / '.gitlab' / 'ci'


def job_block(text, name):
    match = re.search(r'(?ms)^' + re.escape(name) + r':\n(.*?)(?=^[.]?[A-Za-z0-9_-]+:\n|\Z)',
                      text)
    if match is None:
        raise AssertionError('Missing CI job: ' + name)
    return match.group(1)


class CiConfigTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root = CI_ROOT.read_text()
        cls.common = (CI_DIR / 'common.yml').read_text()
        cls.backend = (CI_DIR / 'backend.yml').read_text()
        cls.frontend = (CI_DIR / 'frontend.yml').read_text()
        cls.consumer = (CI_DIR / 'consumer.yml').read_text()
        cls.pipeline_path = CI_DIR / 'pipeline.yml'
        cls.pipeline = cls.pipeline_path.read_text() if cls.pipeline_path.exists() else ''

    def test_root_only_declares_stages_and_reviewed_includes(self):
        self.assertEqual(self.root, '''stages:
  - verify
  - test
  - build
  - deploy

workflow:
  auto_cancel:
    on_new_commit: interruptible

include:
  - local: .gitlab/ci/common.yml
  - local: .gitlab/ci/backend.yml
  - local: .gitlab/ci/frontend.yml
  - local: .gitlab/ci/pipeline.yml
  - local: .gitlab/ci/consumer.yml
''')

    def test_new_commits_cancel_only_interruptible_jobs(self):
        self.assertIn('''workflow:
  auto_cancel:
    on_new_commit: interruptible
''', self.root)

        cancellable = (
            (self.backend, 'backend-env-check'),
            (self.backend, 'backend-test'),
            (self.backend, 'backend-image'),
            (self.frontend, 'frontend-test'),
            (self.frontend, 'frontend-image'),
        )
        for config, name in cancellable:
            self.assertIn('interruptible: true', job_block(config, name), name)

        protected = (
            (self.backend, '.backend-cache-action'),
            (self.backend, 'deploy-backend'),
            (self.frontend, 'deploy-frontend'),
        )
        for config, name in protected:
            self.assertIn('interruptible: false', job_block(config, name), name)

    def test_job_names_are_unique_across_split_files(self):
        names = []
        for text in (self.common, self.backend, self.frontend, self.pipeline, self.consumer):
            names.extend(re.findall(r'(?m)^([.]?[A-Za-z0-9_-]+):$', text))
        duplicates = sorted({name for name in names if names.count(name) > 1})
        self.assertEqual(duplicates, [])

    def test_mr_jobs_share_only_reviewed_common_triggers(self):
        backend = job_block(self.backend, 'backend-mr-test')
        frontend = job_block(self.frontend, 'frontend-mr-test')
        for block in (backend, frontend):
            self.assertIn('tags: [e106-mr-isolated]', block)
            self.assertIn('dependencies: []', block)
            self.assertIn('GIT_STRATEGY: none', block)
            self.assertIn('$CI_PIPELINE_SOURCE == "merge_request_event"', block)
            self.assertIn('$CI_MERGE_REQUEST_TARGET_BRANCH_NAME == "develop"', block)
            for path in ('.gitlab-ci.yml', '.gitlab/ci/common.yml',
                         'infra/scripts/ci-gitlab-job-adapter.py',
                         'infra/scripts/ci-bounded-output.py',
                         'infra/scripts/ci-network-guard.py'):
                self.assertIn('- ' + path, block)
        for path in ('backend/src/**/*', 'backend/scripts/**/*',
                     'backend/config/**/*',
                     'backend/gradle/**/*', 'backend/build.gradle',
                     'backend/gradlew*', 'backend/Dockerfile*', 'backend/.*'):
            self.assertIn('- ' + path, backend)
        self.assertNotIn('- backend/**/*\n', backend)
        self.assertIn('- .gitlab/ci/backend.yml', backend)
        self.assertNotIn('- frontend/**/*', backend)
        self.assertNotIn('- .gitlab/ci/frontend.yml', backend)
        for path in ('frontend/src/**/*', 'frontend/public/**/*',
                     'frontend/index.html', 'frontend/package.json',
                     'frontend/package-lock.json', 'frontend/tsconfig*.json',
                     'frontend/vite.config.*', 'frontend/eslint.config.*',
                     'frontend/.prettier*', 'frontend/Dockerfile*',
                     'frontend/nginx.conf', 'frontend/.dockerignore'):
            self.assertIn('- ' + path, frontend)
        self.assertNotIn('- frontend/**/*\n', frontend)
        self.assertIn('- .gitlab/ci/frontend.yml', frontend)
        self.assertNotIn('- backend/**/*', frontend)
        self.assertNotIn('- .gitlab/ci/backend.yml', frontend)

    def test_consumer_ci_is_included_and_keeps_service_boundaries(self):
        consumer = job_block(self.consumer, 'consumer-mr-test')
        for expected in ('tags: [e106-mr-isolated]', 'dependencies: []',
                         'GIT_STRATEGY: none', 'resource_group: consumer-mr-test',
                         '$CI_PIPELINE_SOURCE == "merge_request_event"',
                         '$CI_MERGE_REQUEST_TARGET_BRANCH_NAME == "develop"',
                         '- pipeline/consumer/**/*', '- .gitlab/ci/consumer.yml',
                         '- infra/scripts/ci-consumer-mr-*.py'):
            self.assertIn(expected, consumer)
        self.assertNotIn('- pipeline/spark/**/*', consumer)
        self.assertNotIn('- backend/**/*', consumer)
        self.assertNotIn('- frontend/**/*', consumer)

    def test_pipeline_mr_jobs_are_isolated_sharded_and_path_scoped(self):
        self.assertTrue(self.pipeline_path.is_file(), 'Missing Pipeline CI include')
        template = job_block(self.pipeline, '.pipeline-mr-test')
        for expected in (
                'stage: test', 'tags: [e106-mr-isolated]', 'dependencies: []',
                'GIT_STRATEGY: none', 'interruptible: true',
                '$CI_PIPELINE_SOURCE == "merge_request_event"',
                '$CI_MERGE_REQUEST_TARGET_BRANCH_NAME == "develop"'):
            self.assertIn(expected, template)
        jobs = {
            'pipeline-unit-test': [
                '- cd pipeline/spark',
                '- uv sync --frozen',
                '- uv run ruff check .',
                '- uv run python -m ci_test_groups verify',
                '- uv run python -m ci_test_groups unit -vv --durations=20',
                '- uv run python -m jobs.batch.als_baseline --help',
                '- uv run python -m jobs.batch.parquet_roundtrip --help',
            ],
            'pipeline-spark-als-a-d-test': [
                '- cd pipeline/spark',
                '- uv sync --frozen',
                '- uv run python -m ci_test_groups spark-als-a-d -vv --durations=20',
            ],
            'pipeline-spark-als-e-z-test': [
                '- cd pipeline/spark',
                '- uv sync --frozen',
                '- uv run python -m ci_test_groups spark-als-e-z -vv --durations=20',
            ],
            'pipeline-spark-other-test': [
                '- cd pipeline/spark',
                '- uv sync --frozen',
                '- uv run python -m ci_test_groups spark-other -vv --durations=20',
            ],
        }
        resource_groups = []
        for name, expected_script in jobs.items():
            job = job_block(self.pipeline, name)
            self.assertIn('extends: .pipeline-mr-test', job)
            self.assertIn('timeout: 15m', job)
            scripts = [line.strip() for line in job.splitlines()
                       if line.strip().startswith('- ')]
            self.assertEqual(scripts, expected_script)
            match = re.search(r'(?m)^  resource_group: (\S+)$', job)
            self.assertIsNotNone(match, name)
            resource_groups.append(match.group(1))
            for forbidden in ('image:', 'services:', 'cache:', 'artifacts:', 'before_script:'):
                self.assertNotIn(forbidden, job)
        self.assertEqual(len(resource_groups), len(set(resource_groups)))
        unit_job = job_block(self.pipeline, 'pipeline-unit-test')
        self.assertNotIn('when: manual', unit_job)
        self.assertNotIn('allow_failure:', unit_job)
        for name in (
                'pipeline-spark-als-a-d-test',
                'pipeline-spark-als-e-z-test',
                'pipeline-spark-other-test'):
            spark_job = job_block(self.pipeline, name)
            self.assertIn('when: manual', spark_job, name)
            self.assertIn('allow_failure: false', spark_job, name)
        for path in (
                'pipeline/spark/**/*.py', 'pipeline/spark/tests/**/*',
                'pipeline/spark/pyproject.toml', 'pipeline/spark/uv.lock',
                'pipeline/spark/.python-version', '.gitlab-ci.yml',
                '.gitlab/ci/common.yml', '.gitlab/ci/pipeline.yml', '.gitattributes',
                'infra/scripts/ci-pipeline-mr-*.py',
                'infra/scripts/test_ci_pipeline_mr.py',
                'infra/scripts/ci-gitlab-job-adapter.py',
                'infra/scripts/ci-gitlab-custom-executor.py',
                'infra/scripts/ci-gitlab-server-install.py',
                'infra/scripts/ci-bounded-output.py',
                'infra/scripts/systemd/feelm-ci222-gitlab-runner.service',
                'infra/scripts/test_ci_gitlab_job_adapter.py',
                'infra/scripts/test_ci_config.py'):
            self.assertIn('- ' + path, template)
        for excluded in ('- pipeline/**/*', '- pipeline/docs/**/*',
                         '- pipeline/consumer/**/*', '- .gitlab/ci/backend.yml',
                         '- .gitlab/ci/frontend.yml'):
            self.assertNotIn(excluded, template)
        for forbidden in ('image:', 'services:', 'cache:', 'artifacts:', 'before_script:'):
            self.assertNotIn(forbidden, template)

    def test_frontend_mr_command_is_fixed_and_has_no_gitlab_runtime_inputs(self):
        block = job_block(self.frontend, 'frontend-mr-test')
        expected = ['- cd frontend', '- npm ci', '- npm run lint',
                    '- npm run format:check', '- npm run build']
        scripts = [line.strip() for line in block.splitlines()
                   if line.strip() in expected]
        self.assertEqual(scripts, expected)
        for forbidden in ('image:', 'services:', 'cache:', 'artifacts:', 'before_script:'):
            self.assertNotIn(forbidden, block)

    def test_main_build_and_deploy_chains_are_protected_and_serialized(self):
        datastore = job_block(self.backend, 'datastore-config-check')
        backend_env = job_block(self.backend, 'backend-env-check')
        backend_test = job_block(self.backend, 'backend-test')
        backend_image = job_block(self.backend, 'backend-image')
        backend_deploy = job_block(self.backend, 'deploy-backend')
        frontend_image = job_block(self.frontend, 'frontend-image')
        frontend_deploy = job_block(self.frontend, 'deploy-frontend')
        main_push = ('$CI_COMMIT_BRANCH == "main" && '
                     '$CI_COMMIT_REF_PROTECTED == "true" && '
                     '$CI_PIPELINE_SOURCE == "push"')
        for block in (datastore, backend_env, backend_test, backend_image, backend_deploy,
                      job_block(self.frontend, 'frontend-test'), frontend_image,
                      frontend_deploy):
            self.assertIn(main_push, block)
            self.assertNotIn('$CI_COMMIT_BRANCH == "develop"', block)
        self.assertIn('job: backend-test', backend_image)
        self.assertIn('job: backend-env-check', backend_image)
        self.assertIn('job: datastore-config-check', backend_image)
        self.assertIn('needs: [backend-image]', backend_deploy)
        for block in (backend_test, backend_image, backend_deploy):
            self.assertIn('changes:', block)
            self.assertIn('- backend/src/**/*', block)
            self.assertIn('- infra/compose/service.yml', block)
            self.assertIn('- infra/scripts/deploy-backend.sh', block)
        self.assertIn('needs: [frontend-test]', frontend_image)
        self.assertIn('needs: [frontend-image]', frontend_deploy)
        for block in (backend_deploy, frontend_deploy):
            self.assertIn('resource_group: service-and-backend-cache', block)
            self.assertIn('ci-deploy-current-commit.py', block)
        self.assertIn('--init-script gradle/test-timing.gradle', backend_test)
        self.assertIn('action: prepare', backend_env)
        self.assertIn('ci-backend-env-contract.py --require-values', backend_env)

    def test_service_release_jobs_exclude_develop_mr_api_and_web_pipelines(self):
        release_jobs = (
            (self.backend, 'datastore-config-check'),
            (self.backend, 'backend-env-check'),
            (self.backend, 'backend-test'),
            (self.backend, 'backend-image'),
            (self.backend, 'deploy-backend'),
            (self.frontend, 'frontend-test'),
            (self.frontend, 'frontend-image'),
            (self.frontend, 'deploy-frontend'),
        )
        required = ('if: \'$CI_COMMIT_BRANCH == "main" && '
                    '$CI_COMMIT_REF_PROTECTED == "true" && '
                    '$CI_PIPELINE_SOURCE == "push"\'')
        for config, name in release_jobs:
            block = job_block(config, name)
            self.assertIn(required, block, name)
            self.assertNotIn('if: \'$CI_COMMIT_BRANCH == "develop"', block, name)
            self.assertNotIn('$CI_PIPELINE_SOURCE == "web"', block, name)

        for config, name in ((self.backend, 'backend-mr-test'),
                             (self.frontend, 'frontend-mr-test')):
            block = job_block(config, name)
            self.assertIn('$CI_PIPELINE_SOURCE == "merge_request_event"', block)
            self.assertIn('$CI_MERGE_REQUEST_TARGET_BRANCH_NAME == "develop"', block)


if __name__ == '__main__':
    unittest.main()
