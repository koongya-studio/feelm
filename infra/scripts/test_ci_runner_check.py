from pathlib import Path, PurePosixPath
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
COMMON = ROOT / '.gitlab' / 'ci' / 'common.yml'


def runner_check_block(text):
    match = re.search(r'(?ms)^runner-check:\n(.*?)(?=^[.]?[A-Za-z0-9_-]+:\n|\Z)', text)
    if match is None:
        raise AssertionError('Missing CI job: runner-check')
    return match.group(1)


def automatic_change_patterns(block):
    match = re.search(
        r'(?m)^    - if: .*CI_PIPELINE_SOURCE == "push".*\n'
        r'      changes:\n((?:        - .+\n)+)',
        block,
    )
    if match is None:
        raise AssertionError('Missing automatic runner-check changes rule')
    return [line.removeprefix('        - ') for line in match.group(1).splitlines()]


def matches_any(path, patterns):
    candidate = PurePosixPath(path)
    return any(candidate.match(pattern) for pattern in patterns)


class RunnerCheckRulesTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.block = runner_check_block(COMMON.read_text())
        cls.patterns = automatic_change_patterns(cls.block)

    def test_automatic_rule_is_limited_to_protected_develop_push(self):
        condition = ('$CI_COMMIT_BRANCH == "develop" && '
                     '$CI_COMMIT_REF_PROTECTED == "true" && '
                     '$CI_PIPELINE_SOURCE == "push"')
        self.assertEqual(self.block.count(condition), 2)
        self.assertNotIn('$CI_COMMIT_BRANCH == "develop"\'\n', self.block)

    def test_runner_and_common_ci_changes_match_automatic_rule(self):
        related = (
            '.gitlab-ci.yml',
            '.gitlab/ci/common.yml',
            'infra/scripts/ci-gitlab-server-install.py',
            'infra/scripts/ci-guard-service.py',
            'infra/scripts/ci-network-guard.py',
            'infra/scripts/systemd/feelm-ci222-gitlab-runner.service',
        )
        for path in related:
            with self.subTest(path=path):
                self.assertTrue(matches_any(path, self.patterns))

    def test_application_and_document_changes_do_not_match_automatic_rule(self):
        unrelated = (
            'backend/src/main/java/com/feelm/App.java',
            'frontend/src/App.tsx',
            'backend/README.md',
            'docs/README.md',
            'infra/README.md',
            '.gitlab/ci/backend.yml',
            '.gitlab/ci/frontend.yml',
        )
        for path in unrelated:
            with self.subTest(path=path):
                self.assertFalse(matches_any(path, self.patterns))

    def test_unrelated_develop_push_keeps_non_blocking_manual_check(self):
        manual_rule = (
            '    - if: \'$CI_COMMIT_BRANCH == "develop" && '
            '$CI_COMMIT_REF_PROTECTED == "true" && '
            '$CI_PIPELINE_SOURCE == "push"\'\n'
            '      when: manual\n'
            '      allow_failure: true'
        )
        self.assertIn(manual_rule, self.block)

    def test_comment_review_api_pipeline_stays_excluded(self):
        self.assertIn(
            '    - if: \'$CI_PIPELINE_SOURCE == "api" && '
            '$CLAUDE_REVIEW_TRIGGER == "comment"\'\n'
            '      when: never',
            self.block,
        )


if __name__ == '__main__':
    unittest.main()
