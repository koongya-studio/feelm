from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
DOCS_CI = ROOT / '.gitlab' / 'ci' / 'docs.yml'


def gitlab_glob_regex(pattern):
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
        else:
            output += re.escape(pattern[index])
            index += 1
    return re.compile(output + '$')


class DocsPathRulesTests(unittest.TestCase):
    def test_every_develop_mr_change_creates_documentation_job(self):
        text = DOCS_CI.read_text()
        self.assertIn('$CI_PIPELINE_SOURCE == "merge_request_event"', text)
        self.assertIn('$CI_MERGE_REQUEST_TARGET_BRANCH_NAME == "develop"', text)
        match = re.search(r'(?m)^        - "(?P<pattern>\*\*/\*)"$', text)
        self.assertIsNotNone(match)
        pattern = gitlab_glob_regex(match.group('pattern'))
        for representative in (
                'README.md', 'docs/guide.md', 'docs/image.png', 'backend/src/A.java',
                'frontend/src/main.tsx', 'removed/linked-target.json',
                'infra/scripts/check-markdown.py', '.gitlab/ci/docs.yml'):
            with self.subTest(path=representative):
                self.assertIsNotNone(pattern.fullmatch(representative))


if __name__ == '__main__':
    unittest.main()
