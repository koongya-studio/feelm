import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest


HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("check_markdown", HERE / "check-markdown.py")
checker = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = checker
SPEC.loader.exec_module(checker)


class MarkdownCheckTests(unittest.TestCase):
    def run_check(self, files):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, content in files.items():
                target = root.joinpath(*Path(name).parts)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(content if isinstance(content, bytes) else content.encode("utf-8"))
            return checker.check(root, sorted(files))

    def test_accepts_relative_korean_space_image_and_external_links(self):
        problems = self.run_check({
            "README.md": "[가이드](<docs/사용 설명.md>)\n[외부](https://example.com/x)\n",
            "docs/사용 설명.md": "![이미지](<이미지/포스터 1.png>)\n",
            "docs/이미지/포스터 1.png": b"png",
        })
        self.assertEqual(problems, [])

    def test_reports_missing_file_at_source_location(self):
        problems = self.run_check({"docs/README.md": "앞 [링크](missing.md) 뒤\n"})
        self.assertEqual(len(problems), 1)
        self.assertIn("docs/README.md:1:", problems[0])
        self.assertIn("LINK missing tracked target", problems[0])

    def test_deleted_or_moved_target_breaks_reverse_link(self):
        files = {"README.md": "[계약](docs/contract.md)\n", "docs/contract.md": "ok\n"}
        self.assertEqual(self.run_check(files), [])
        files.pop("docs/contract.md")
        self.assertIn("missing tracked target", self.run_check(files)[0])

    def test_ignores_markers_and_links_inside_fenced_or_inline_code(self):
        text = "`[예시](missing.md)`\n```text\n<<<<<<< HEAD\n[x](missing.md)\n=======\n>>>>>>> x\n```\n"
        self.assertEqual(self.run_check({"README.md": text}), [])

    def test_reports_conflict_unclosed_link_and_fence(self):
        problems = self.run_check({"README.md": "<<<<<<< HEAD\n[x](missing.md\n```\n"})
        self.assertEqual([problem.split(": ")[1].split()[0] for problem in problems],
                         ["CONFLICT", "SYNTAX", "SYNTAX"])

    def test_rejects_escape_and_unknown_scheme(self):
        problems = self.run_check({"docs/README.md": "[a](../../secret) [b](javascript:alert(1))\n"})
        self.assertEqual(len(problems), 2)
        self.assertIn("escapes the repository", problems[0])
        self.assertIn("unsupported link scheme", problems[1])

    def test_manifest_must_be_sorted_unique_json(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "paths.json"
            manifest.write_text(json.dumps(["b.md", "a.md"]), encoding="utf-8")
            with self.assertRaises(checker.CheckError):
                checker.tracked_paths(root, manifest)


if __name__ == "__main__":
    unittest.main()
