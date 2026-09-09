import importlib.util
import os
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("ci-deploy-current-commit.py")
SPEC = importlib.util.spec_from_file_location("ci_deploy_current_commit", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class DeployCurrentCommitTest(unittest.TestCase):
    commit = "1" * 40

    def run_main(self, *, branch="develop", remote_sha=None, returncode=0):
        completed = subprocess.CompletedProcess(
            [], returncode, f"{remote_sha or self.commit}\trefs/heads/{branch}\n", ""
        )
        with patch.dict(
            os.environ,
            {"CI_COMMIT_SHA": self.commit, "CI_COMMIT_BRANCH": branch},
            clear=True,
        ), patch.object(MODULE.subprocess, "run", return_value=completed) as run:
            result = MODULE.main()
        return result, run

    def test_accepts_current_branch_head(self):
        result, run = self.run_main()

        self.assertEqual(result, 0)
        run.assert_called_once_with(
            ["git", "ls-remote", "--exit-code", "origin", "refs/heads/develop"],
            check=False,
            capture_output=True,
            text=True,
        )

    def test_rejects_outdated_commit(self):
        result, _ = self.run_main(remote_sha="2" * 40)

        self.assertEqual(result, 1)

    def test_fails_closed_when_remote_lookup_fails(self):
        result, _ = self.run_main(returncode=2)

        self.assertEqual(result, 2)

    def test_rejects_invalid_branch_before_running_git(self):
        result, run = self.run_main(branch="--upload-pack=bad")

        self.assertEqual(result, 2)
        run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
