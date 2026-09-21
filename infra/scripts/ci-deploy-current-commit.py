#!/usr/bin/env python3
"""Reject a deployment when its commit is no longer the branch head."""

from __future__ import annotations

import os
import re
import subprocess
import sys
import argparse


SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
DEPLOY_BRANCHES = ("main", "develop")
PIPELINE_SOURCES = ("push", "parent_pipeline", "web")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--branch", choices=DEPLOY_BRANCHES, default="main")
    parser.add_argument("--pipeline-source", choices=PIPELINE_SOURCES, default="push")
    args = parser.parse_args()
    commit_sha = os.environ.get("CI_COMMIT_SHA", "")
    branch = os.environ.get("CI_COMMIT_BRANCH", "")
    protected = os.environ.get("CI_COMMIT_REF_PROTECTED", "")
    source = os.environ.get("CI_PIPELINE_SOURCE", "")

    if not SHA_PATTERN.fullmatch(commit_sha):
        print("CI_COMMIT_SHA must be a full commit SHA", file=sys.stderr)
        return 2
    if branch != args.branch or protected != "true" or source != args.pipeline_source:
        print(
            f"Deployment requires a protected {args.branch} "
            f"{args.pipeline_source} pipeline",
            file=sys.stderr,
        )
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
