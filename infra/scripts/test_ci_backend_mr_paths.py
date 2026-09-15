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
        raise AssertionError('Missing backend-mr-test changes rule')
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


class BackendMrPathRulesTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ci = BACKEND_CI.read_text()
        cls.block = job_block(cls.ci, 'backend-mr-test')
        cls.patterns = change_patterns(cls.block)

    def test_backend_markdown_only_changes_are_excluded(self):
        documents = (
            'backend/README.md',
            'backend/AGENTS.md',
            'backend/CLAUDE.md',
            'backend/docs/operations.md',
            'backend/verification/S15P21E106-375.md',
        )
        for path in documents:
            with self.subTest(path=path):
                self.assertFalse(matches(path, self.patterns))
        self.assertFalse(should_run(documents, self.patterns))

    def test_backend_runtime_and_validation_inputs_are_included(self):
        inputs = (
            'backend/src/main/java/com/feelm/backend/BackendApplication.java',
            'backend/src/main/resources/application.yml',
            'backend/src/test/java/com/feelm/backend/BackendApplicationTests.java',
            'backend/src/test/resources/application.yml',
            'backend/scripts/run-initial-data-load.sh',
            'backend/scripts/samples/sample-expected.json',
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
            'backend/.gitattributes',
            'backend/.gitignore',
        )
        for path in inputs:
            with self.subTest(path=path):
                self.assertTrue(matches(path, self.patterns))

    def test_common_ci_and_isolated_runner_inputs_remain_included(self):
        inputs = (
            '.gitlab-ci.yml',
            '.gitlab/ci/common.yml',
            '.gitlab/ci/backend.yml',
            '.gitattributes',
            'infra/scripts/ci-gitlab-job-adapter.py',
            'infra/scripts/ci-job-safety.py',
            'infra/scripts/ci-local-backend-runtime.py',
            'infra/scripts/ci-network-guard.py',
            'infra/scripts/rootless-backend/pilot.init.gradle',
            'infra/scripts/systemd/feelm-ci222-gitlab-runner.service',
            'infra/scripts/test_ci_config.py',
            'infra/scripts/test_ci_backend_mr_paths.py',
        )
        for path in inputs:
            with self.subTest(path=path):
                self.assertTrue(matches(path, self.patterns))

    def test_mixed_change_and_document_after_code_still_run(self):
        changes = ('backend/src/main/resources/application.yml', 'backend/README.md')
        self.assertTrue(should_run(changes, self.patterns))

    def test_delete_and_rename_scenarios_use_all_changed_paths(self):
        deleted_source = ('backend/src/main/resources/application.yml',)
        document_rename = ('backend/README.md', 'backend/docs/README.md')
        source_to_document = (
            'backend/src/main/resources/legacy.yml',
            'backend/docs/legacy.md',
        )
        self.assertTrue(should_run(deleted_source, self.patterns))
        self.assertFalse(should_run(document_rename, self.patterns))
        self.assertTrue(should_run(source_to_document, self.patterns))

    def test_dependency_cache_paths_are_covered_without_changing_cache_rule(self):
        cache_patterns = change_patterns(job_block(self.ci, 'backend-cache-prepare'))
        for pattern in cache_patterns:
            representative = pattern.replace('*', 'example')
            with self.subTest(pattern=pattern):
                self.assertTrue(matches(representative, self.patterns))

    def test_broad_backend_rule_is_removed_and_mr_scope_is_preserved(self):
        self.assertNotIn('- backend/**/*\n', self.block)
        self.assertIn('$CI_PIPELINE_SOURCE == "merge_request_event"', self.block)
        self.assertIn('$CI_MERGE_REQUEST_TARGET_BRANCH_NAME == "develop"', self.block)
        self.assertIn('- infra/scripts/test_ci_backend_mr_paths.py', self.block)


if __name__ == '__main__':
    unittest.main()
