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
        for text in (self.common, self.backend, self.frontend):
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
