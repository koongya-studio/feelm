from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
FRONTEND_CI = ROOT / '.gitlab' / 'ci' / 'frontend.yml'


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


class FrontendPathRulesTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ci = FRONTEND_CI.read_text()
        cls.blocks = {
            name: job_block(cls.ci, name)
            for name in ('frontend-mr-test', 'frontend-test', 'frontend-image',
                         'deploy-frontend')
        }
        cls.patterns = {
            name: change_patterns(block) for name, block in cls.blocks.items()
        }

    def test_document_and_independent_landing_changes_are_excluded(self):
        documents = (
            'frontend/README.md',
            'frontend/docs/screens/README.md',
            'frontend/verification/S15P21E106-376.md',
            'frontend/landing/index.html',
            'frontend/landing/movies.json',
            'frontend/landing/posters/01.jpg',
        )
        for job, patterns in self.patterns.items():
            with self.subTest(job=job):
                self.assertFalse(should_run(documents, patterns))

    def test_environment_example_is_not_a_build_or_deploy_input(self):
        for job, patterns in self.patterns.items():
            with self.subTest(job=job):
                self.assertFalse(matches('frontend/.env.example', patterns))

    def test_runtime_build_and_container_inputs_are_included(self):
        inputs = (
            'frontend/src/main.tsx',
            'frontend/src/api/client.test.ts',
            'frontend/public/favicon.svg',
            'frontend/index.html',
            'frontend/package.json',
            'frontend/package-lock.json',
            'frontend/tsconfig.json',
            'frontend/tsconfig.app.json',
            'frontend/vite.config.ts',
            'frontend/eslint.config.js',
            'frontend/.prettierignore',
            'frontend/.prettierrc.json',
            'frontend/Dockerfile',
            'frontend/Dockerfile.dev',
            'frontend/nginx.conf',
            'frontend/.dockerignore',
        )
        for job, patterns in self.patterns.items():
            for path in inputs:
                with self.subTest(job=job, path=path):
                    self.assertTrue(matches(path, patterns))

    def test_mixed_change_and_document_after_code_still_run(self):
        changes = ('frontend/src/main.tsx', 'frontend/README.md')
        for job, patterns in self.patterns.items():
            with self.subTest(job=job):
                self.assertTrue(should_run(changes, patterns))

    def test_delete_and_rename_scenarios_use_all_changed_paths(self):
        deleted_source = ('frontend/src/legacy.ts',)
        document_rename = ('frontend/README.md', 'frontend/docs/README.md')
        source_to_document = ('frontend/src/legacy.ts', 'frontend/docs/legacy.md')
        for job, patterns in self.patterns.items():
            with self.subTest(job=job):
                self.assertTrue(should_run(deleted_source, patterns))
                self.assertFalse(should_run(document_rename, patterns))
                self.assertTrue(should_run(source_to_document, patterns))

    def test_frontend_runtime_patterns_match_across_the_job_chain(self):
        runtime_prefixes = ('frontend/',)
        expected = {
            pattern for pattern in self.patterns['frontend-mr-test']
            if pattern.startswith(runtime_prefixes)
        }
        for job in ('frontend-test', 'frontend-image', 'deploy-frontend'):
            actual = {
                pattern for pattern in self.patterns[job]
                if pattern.startswith(runtime_prefixes)
            }
            self.assertEqual(actual, expected, job)

    def test_deploy_inputs_keep_the_develop_chain_aligned(self):
        deploy_inputs = (
            'infra/compose/service.yml',
            'infra/nginx/nginx.conf',
            'infra/scripts/deploy-frontend.sh',
            'infra/scripts/rollback-frontend.sh',
            'infra/scripts/test_deploy_frontend.py',
            '.gitlab-ci.yml',
            '.gitlab/ci/common.yml',
            '.gitlab/ci/frontend.yml',
            '.gitattributes',
        )
        for path in deploy_inputs:
            for job in ('frontend-test', 'frontend-image', 'deploy-frontend'):
                with self.subTest(job=job, path=path):
                    self.assertTrue(matches(path, self.patterns[job]))

    def test_mr_scope_and_dedicated_test_trigger_are_preserved(self):
        block = self.blocks['frontend-mr-test']
        self.assertNotIn('- frontend/**/*\n', block)
        self.assertIn('$CI_PIPELINE_SOURCE == "merge_request_event"', block)
        self.assertIn('$CI_MERGE_REQUEST_TARGET_BRANCH_NAME == "develop"', block)
        self.assertIn('- infra/scripts/test_ci_frontend_paths.py', block)
        self.assertIn(
            'python3 -B -m unittest infra.scripts.test_ci_frontend_paths -v',
            self.blocks['frontend-test'],
        )


if __name__ == '__main__':
    unittest.main()
