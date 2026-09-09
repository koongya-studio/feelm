#!/usr/bin/env python3
"""Reject a deployment when its commit is no longer the branch head."""

from __future__ import annotations

import os
import re
import subprocess
import sys


SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
BRANCH_PATTERN = re.compile(r"^[A-Za-z0-9._/-]+$")


def main() -> int:
    commit_sha = os.environ.get("CI_COMMIT_SHA", "")
    branch = os.environ.get("CI_COMMIT_BRANCH", "")

    if not SHA_PATTERN.fullmatch(commit_sha):
        print("CI_COMMIT_SHA must be a full commit SHA", file=sys.stderr)
        return 2
    if not BRANCH_PATTERN.fullmatch(branch) or branch.startswith("-") or ".." in branch:
        print("CI_COMMIT_BRANCH is invalid", file=sys.stderr)
        return 2

    result = subprocess.run(
        ["git", "ls-remote", "--exit-code", "origin", f"refs/heads/{branch}"],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        print("Could not resolve the current deployment branch head", file=sys.stderr)
        return 2

    fields = result.stdout.split()
    remote_sha = fields[0] if fields else ""
    if not SHA_PATTERN.fullmatch(remote_sha):
        print("Remote branch head was not a full commit SHA", file=sys.stderr)
        return 2

    if remote_sha != commit_sha:
        print(
            f"Skipping outdated deployment commit {commit_sha}; "
            f"{branch} now points to {remote_sha}",
            file=sys.stderr,
        )
        return 1

    print(f"Deployment commit is current: {commit_sha}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
