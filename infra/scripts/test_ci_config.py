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
        cls.docs = (CI_DIR / 'docs.yml').read_text()
        cls.backend = (CI_DIR / 'backend.yml').read_text()
        cls.frontend = (CI_DIR / 'frontend.yml').read_text()
        cls.consumer = (CI_DIR / 'consumer.yml').read_text()
        cls.infra = (CI_DIR / 'infra.yml').read_text()
        cls.hdfs_deploy = (ROOT / 'infra' / 'scripts' / 'deploy-hdfs-config.sh').read_text()
        cls.kafka_deploy = (ROOT / 'infra' / 'scripts' / 'deploy-kafka-config.sh').read_text()
        cls.pipeline_path = CI_DIR / 'pipeline.yml'
        cls.pipeline = cls.pipeline_path.read_text() if cls.pipeline_path.exists() else ''

    def test_root_only_declares_stages_and_reviewed_includes(self):
        self.assertEqual(self.root, '''stages:
  - verify
  - test
  - build
  - deploy
  - notify

workflow:
  auto_cancel:
    on_new_commit: interruptible

include:
  - local: .gitlab/ci/common.yml
  - local: .gitlab/ci/docs.yml
  - local: .gitlab/ci/backend.yml
  - local: .gitlab/ci/frontend.yml
  - local: .gitlab/ci/pipeline.yml
  - local: .gitlab/ci/consumer.yml
  - local: .gitlab/ci/infra.yml
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
            (self.consumer, 'consumer-test'),
            (self.consumer, 'consumer-image'),
        )
        for config, name in cancellable:
            self.assertIn('interruptible: true', job_block(config, name), name)

        protected = (
            (self.backend, '.backend-cache-action'),
            (self.backend, 'deploy-backend'),
            (self.frontend, 'deploy-frontend'),
            (self.consumer, 'deploy-consumer'),
            (self.infra, 'deploy-monitoring'),
            (self.infra, '.deploy-hdfs-config'),
        )
        for config, name in protected:
            self.assertIn('interruptible: false', job_block(config, name), name)

    def test_job_names_are_unique_across_split_files(self):
        names = []
        for text in (self.common, self.backend, self.frontend, self.pipeline, self.consumer,
                     self.infra, self.docs):
            names.extend(re.findall(r'(?m)^([.]?[A-Za-z0-9_-]+):$', text))
        duplicates = sorted({name for name in names if names.count(name) > 1})
        self.assertEqual(duplicates, [])

    def test_jira_mattermost_digest_is_schedule_only(self):
        digest = job_block(self.common, 'jira-mattermost-digest')
        condition = ('$CI_PIPELINE_SOURCE == "schedule" && '
                     '$CI_COMMIT_BRANCH == "develop" && '
                     '$CI_COMMIT_REF_PROTECTED == "true" && '
                     '$JIRA_MATTERMOST_DIGEST == "true"')
        self.assertIn(condition, digest)
        self.assertIn('python3 -B -m unittest tests.jira.test_notify_mattermost '
                      'infra.scripts.test_ci_config -v', digest)
        self.assertIn('python3 -I -B scripts/jira/notify_mattermost.py', digest)
        self.assertIn('resource_group: jira-mattermost-digest', digest)
        self.assertIn('interruptible: false', digest)
        for secret in ('JIRA_API_TOKEN', 'MATTERMOST_LOGIN_ID', 'MATTERMOST_PASSWORD'):
            self.assertNotIn(secret, digest)

    def test_develop_pipeline_notifies_only_after_predeploy_failure(self):
        notification = job_block(self.common, 'develop-pipeline-failure-notification')
        for expected in (
                'stage: notify', 'tags: [e106-shell]',
                'notify-deployment.py pipeline-failure', 'interruptible: false',
                'allow_failure: true', 'timeout: 5m',
                '$CI_COMMIT_BRANCH == "develop"',
                '$CI_COMMIT_REF_PROTECTED == "true"',
                '$CI_PIPELINE_SOURCE == "push"', 'when: always'):
            self.assertIn(expected, notification)
        for forbidden in ('merge_request_event', '$CI_COMMIT_BRANCH == "main"', 'when: manual'):
            self.assertNotIn(forbidden, notification)

    def test_docs_job_is_fixed_isolated_and_runs_for_every_develop_mr(self):
        block = job_block(self.docs, 'docs-mr-check')
        for expected in (
                'stage: verify', 'tags: [e106-mr-isolated]', 'dependencies: []',
                'GIT_STRATEGY: none', 'interruptible: true', 'timeout: 5m',
                '$CI_PIPELINE_SOURCE == "merge_request_event"',
                '$CI_MERGE_REQUEST_TARGET_BRANCH_NAME == "develop"',
                '- python3 -B infra/scripts/check-markdown.py', '- "**/*"'):
            self.assertIn(expected, block)
        for forbidden in ('image:', 'services:', 'cache:', 'artifacts:', 'before_script:',
                          'allow_failure:', 'when: manual'):
            self.assertNotIn(forbidden, block)

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

    def test_infra_config_ci_is_isolated_and_path_scoped(self):
        infra = job_block(self.infra, 'infra-config-test')
        for expected in (
                'tags: [e106-mr-isolated]', 'dependencies: []', 'GIT_STRATEGY: none',
                'resource_group: infra-config-test',
                '$CI_PIPELINE_SOURCE == "merge_request_event"',
                '$CI_MERGE_REQUEST_TARGET_BRANCH_NAME == "develop"',
                '- infra/compose/**/*', '- infra/monitoring/**/*',
                '- infra/scripts/test_consumer_distributed_deployment.py'):
            self.assertIn(expected, infra)
        for excluded in ('- backend/**/*', '- frontend/**/*', '- pipeline/consumer/**/*',
                         '- pipeline/spark/**/*'):
            self.assertNotIn(excluded, infra)
        self.assertNotIn('- infra/scripts/test_consumer_distributed_deployment.py',
                         job_block(self.backend, 'backend-mr-test'))

    def test_infra_config_changes_stays_within_gitlab_limit(self):
        infra = job_block(self.infra, 'infra-config-test')
        changes = re.search(r'(?m)^      changes:\n((?:        - .*\n)+)', infra)
        self.assertIsNotNone(changes)
        entries = re.findall(r'(?m)^        - ', changes.group(1))
        self.assertLessEqual(len(entries), 50)
        self.assertIn('- infra/scripts/deploy-*-config.sh', changes.group(1))

    def test_required_variable_contract_and_runtime_preflight_are_separated(self):
        contract = job_block(self.infra, 'ci-variable-contract-check')
        for expected in (
                'stage: verify', 'tags: [e106-mr-isolated]', 'dependencies: []',
                'GIT_STRATEGY: none', 'resource_group: ci-variable-contract-check',
                'interruptible: true', 'timeout: 5m',
                '$CI_PIPELINE_SOURCE == "merge_request_event"',
                '$CI_MERGE_REQUEST_TARGET_BRANCH_NAME == "develop"',
                'ci-required-variable-preflight.py'):
            self.assertIn(expected, contract)
        for forbidden in ('CI_VARIABLE_AUDIT_TOKEN', '--require-values', '--check-metadata',
                          'environment:', 'tags: [e106-shell]'):
            self.assertNotIn(forbidden, contract)

        preflight = job_block(self.infra, 'ci-variable-preflight')
        for expected in (
                'stage: verify', 'tags: [e106-shell]', 'needs: []',
                'name: service', 'action: prepare',
                '--require-values --check-metadata',
                '$CI_COMMIT_BRANCH == "develop"',
                '$CI_COMMIT_REF_PROTECTED == "true"',
                '$CI_PIPELINE_SOURCE == "push"',
                'resource_group: ci-variable-preflight', 'interruptible: true'):
            self.assertIn(expected, preflight)
        for forbidden in ('merge_request_event', 'when: manual', 'allow_failure: true'):
            self.assertNotIn(forbidden, preflight)

        cache = job_block(self.backend, 'backend-cache-prepare')
        self.assertIn('needs:\n    - job: ci-variable-contract-check\n      artifacts: false', cache)
        self.assertIn('dependencies: []', cache)

    def test_monitoring_deploy_is_protected_current_develop_only(self):
        deploy = job_block(self.infra, 'deploy-monitoring')
        for expected in (
                'stage: deploy', 'tags: [e106-shell]',
                'needs: [ci-variable-preflight]', 'name: service',
                'ci-deploy-current-commit.py --branch develop',
                'bash infra/scripts/deploy-monitoring.sh',
                'resource_group: service-and-backend-cache',
                'interruptible: false', 'timeout: 15m',
                '$CI_COMMIT_BRANCH == "develop"',
                '$CI_COMMIT_REF_PROTECTED == "true"',
                '$CI_PIPELINE_SOURCE == "push"',
                '- infra/compose/monitoring.yml',
                '- infra/monitoring/prometheus/**/*',
                '- infra/monitoring/grafana/**/*',
                '- infra/config/required-ci-variables.json'):
            self.assertIn(expected, deploy)
        for forbidden in (
                '$CI_COMMIT_BRANCH == "main"', 'merge_request_event',
                'when: manual', 'allow_failure: true', 'docker compose down',
                '- infra/monitoring/README.md'):
            self.assertNotIn(forbidden, deploy)

    def test_hdfs_config_deploy_is_manual_protected_main_and_ordered(self):
        template = job_block(self.infra, '.deploy-hdfs-config')
        for expected in (
                'stage: deploy', 'ci-deploy-current-commit.py',
                'deploy-hdfs-config.sh "$HDFS_NODE" "$CI_COMMIT_SHA"',
                'resource_group: distributed-runtime-hdfs',
                'interruptible: false', 'timeout: 30m',
                '$CI_COMMIT_BRANCH == "main"',
                '$CI_COMMIT_REF_PROTECTED == "true"',
                '$CI_PIPELINE_SOURCE == "push"',
                'when: manual', 'allow_failure: false',
                '- infra/compose/service.yml', '- infra/compose/distributed.yml',
                '- infra/hadoop/**/*'):
            self.assertIn(expected, template)
        for forbidden in ('merge_request_event', 'docker compose down', ' down -v',
                          'HDFS_COMPOSE_ENV_FILE'):
            self.assertNotIn(forbidden, template)
        self.assertIn('ENV_FILE="$(mktemp ', self.hdfs_deploy)
        self.assertIn("trap 'rm -f \"${ENV_FILE}\"' EXIT", self.hdfs_deploy)
        self.assertNotIn('HDFS_COMPOSE_ENV_FILE', self.hdfs_deploy)
        self.assertIn('if [[ "${NODE}" == "service" ]]', self.hdfs_deploy)
        self.assertIn("docker inspect feelm-backend --format '{{.Config.Image}}'",
                      self.hdfs_deploy)
        self.assertIn('export BACKEND_IMAGE', self.hdfs_deploy)
        self.assertIn('Running backend image could not be resolved', self.hdfs_deploy)
        service = job_block(self.infra, 'deploy-hdfs-service-config')
        distributed = job_block(self.infra, 'deploy-hdfs-distributed-config')
        self.assertIn('tags: [e106-shell]', service)
        self.assertIn('name: service', service)
        self.assertIn('HDFS_NODE: service', service)
        self.assertIn('tags: [e106-distributed]', distributed)
        self.assertIn('needs: [deploy-hdfs-service-config]', distributed)
        self.assertIn('name: distributed', distributed)
        self.assertIn('HDFS_NODE: distributed', distributed)

    def test_kafka_config_deploy_is_manual_protected_main_and_isolated(self):
        deploy = job_block(self.infra, 'deploy-kafka-config')
        for expected in (
                'stage: deploy', 'tags: [e106-distributed]', 'needs: []',
                'name: distributed', 'ci-deploy-current-commit.py',
                'deploy-kafka-config.sh "$CI_COMMIT_SHA"',
                'notify-deployment.py kafka-config "$CI_JOB_STATUS"',
                'resource_group: distributed-runtime-kafka',
                'interruptible: false', 'timeout: 20m',
                '$CI_COMMIT_BRANCH == "main"',
                '$CI_COMMIT_REF_PROTECTED == "true"',
                '$CI_PIPELINE_SOURCE == "push"',
                'when: manual', 'allow_failure: false',
                '- infra/compose/distributed.yml', '- infra/kafka/**/*',
                '- infra/scripts/deploy-kafka-config.sh'):
            self.assertIn(expected, deploy)
        for forbidden in ('merge_request_event', 'docker compose down', ' down -v',
                          'KAFKA_CLUSTER_ID='):
            self.assertNotIn(forbidden, deploy)
        for expected in ('--component kafka --node distributed',
                         'docker inspect feelm-kafka', 'running_cluster_id',
                         'KAFKA_CLUSTER_ID is required', '--components kafka'):
            self.assertIn(expected, self.kafka_deploy)
        self.assertNotIn('echo "${KAFKA_CLUSTER_ID}', self.kafka_deploy)

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
        consumer_test = job_block(self.consumer, 'consumer-test')
        consumer_image = job_block(self.consumer, 'consumer-image')
        consumer_deploy = job_block(self.consumer, 'deploy-consumer')
        main_push = ('$CI_COMMIT_BRANCH == "main" && '
                     '$CI_COMMIT_REF_PROTECTED == "true" && '
                     '$CI_PIPELINE_SOURCE == "push"')
        for block in (datastore, backend_env, backend_test, backend_image, backend_deploy,
                      job_block(self.frontend, 'frontend-test'), frontend_image,
                      frontend_deploy, consumer_test, consumer_image, consumer_deploy):
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
        self.assertIn('needs: [consumer-test]', consumer_image)
        self.assertIn('needs: [consumer-image]', consumer_deploy)
        for block in (backend_deploy, frontend_deploy):
            self.assertIn('resource_group: service-and-backend-cache', block)
            self.assertIn('ci-deploy-current-commit.py', block)
        self.assertIn('resource_group: distributed-consumer', consumer_deploy)
        self.assertIn('tags: [e106-distributed]', consumer_deploy)
        self.assertIn('ci-deploy-current-commit.py', consumer_deploy)
        deploy_configs = (self.backend, self.frontend, self.consumer, self.infra)
        deploy_jobs = []
        for config in deploy_configs:
            for name in re.findall(r'(?m)^(deploy-[A-Za-z0-9_-]+):$', config):
                deploy_jobs.append(name)
                block = job_block(config, name)
                service = name.removeprefix('deploy-')
                self.assertIn(
                    'infra/scripts/notify-deployment.py ' + service + ' "$CI_JOB_STATUS"',
                    block,
                    name,
                )
                self.assertIn('after_script:', block, name)
        self.assertEqual(
            sorted(deploy_jobs),
            ['deploy-backend', 'deploy-consumer', 'deploy-frontend',
             'deploy-hdfs-distributed-config', 'deploy-hdfs-service-config',
             'deploy-kafka-config', 'deploy-monitoring', 'deploy-redis'],
        )
        notification_paths = (
            backend_env,
            backend_test,
            backend_image,
            backend_deploy,
            job_block(self.frontend, 'frontend-test'),
            frontend_image,
            frontend_deploy,
            consumer_test,
            consumer_image,
            consumer_deploy,
        )
        for block in notification_paths:
            self.assertIn('- infra/scripts/notify-deployment.py', block)
            self.assertIn('- infra/scripts/test_notify_deployment.py', block)
        infra_test = job_block(self.infra, 'infra-config-test')
        self.assertIn('- infra/scripts/notify-deployment.py', infra_test)
        self.assertIn('- infra/scripts/test_notify_deployment.py', infra_test)
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
            (self.consumer, 'consumer-test'),
            (self.consumer, 'consumer-image'),
            (self.consumer, 'deploy-consumer'),
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
