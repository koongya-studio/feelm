import hashlib
import importlib.util
import io
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch


HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
CONSUMER = ROOT / "pipeline" / "consumer"
CI_CONFIG = ROOT / ".gitlab" / "ci" / "consumer.yml"


def load_required(name, filename):
    path = HERE / filename
    if not path.is_file():
        raise AssertionError("Missing required implementation: " + filename)
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def snapshot(entries=None):
    values = {
        "pipeline/consumer/.python-version": (CONSUMER / ".python-version").read_bytes(),
        "pipeline/consumer/pyproject.toml": (CONSUMER / "pyproject.toml").read_bytes(),
        "pipeline/consumer/uv.lock": (CONSUMER / "uv.lock").read_bytes(),
        "pipeline/consumer/Dockerfile": (CONSUMER / "Dockerfile").read_bytes(),
        "pipeline/consumer/.dockerignore": (CONSUMER / ".dockerignore").read_bytes(),
        "pipeline/consumer/.env.example": (CONSUMER / ".env.example").read_bytes(),
        "pipeline/consumer/src/feelm_consumer/example.py": b"VALUE = 1\n",
    }
    values.update(entries or {})
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w") as archive:
        for name in (
            "pipeline/",
            "pipeline/consumer/",
            "pipeline/consumer/src/",
            "pipeline/consumer/src/feelm_consumer/",
        ):
            item = tarfile.TarInfo(name)
            item.type = tarfile.DIRTYPE
            item.mode = 0o755
            archive.addfile(item)
        for name, data in values.items():
            item = tarfile.TarInfo(name)
            item.size = len(data)
            item.mode = 0o644
            archive.addfile(item, io.BytesIO(data))
    return output.getvalue()


def job_block(text, name):
    match = re.search(
        r"(?ms)^" + re.escape(name) + r":\n(.*?)(?=^[.]?[A-Za-z0-9_-]+:\n|\Z)",
        text,
    )
    if match is None:
        raise AssertionError("Missing Consumer CI job: " + name)
    return match.group(1)


class ConsumerBoundaryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.boundary = load_required("test_consumer_boundary", "ci-consumer-mr-boundary.py")

    def test_accepts_only_locked_consumer_project(self):
        payload = snapshot()
        files, _ = self.boundary.source_plan(payload, hashlib.sha256(payload).hexdigest())
        self.assertIn("pipeline/consumer/uv.lock", files)
        self.assertTrue(
            all(name == "pipeline" or name.startswith("pipeline/consumer") for name in files)
        )

    def test_rejects_out_of_scope_secret_generated_link_and_executable(self):
        for payload in (
            snapshot({"pipeline/spark/outside.py": b"outside\n"}),
            snapshot({"pipeline/consumer/.env": b"SECRET=value\n"}),
            snapshot({"pipeline/consumer/.venv/bin/python": b"binary\n"}),
        ):
            with self.subTest(digest=hashlib.sha256(payload).hexdigest()), self.assertRaises(
                ValueError
            ):
                self.boundary.source_plan(payload, hashlib.sha256(payload).hexdigest())

        linked = io.BytesIO()
        with tarfile.open(fileobj=linked, mode="w") as archive:
            item = tarfile.TarInfo("pipeline/consumer/link")
            item.type = tarfile.SYMTYPE
            item.linkname = "/etc/passwd"
            archive.addfile(item)
        payload = linked.getvalue()
        with self.assertRaises(ValueError):
            self.boundary.source_plan(payload, hashlib.sha256(payload).hexdigest())

        original = io.BytesIO(snapshot())
        changed = io.BytesIO()
        with tarfile.open(fileobj=original, mode="r:") as source, tarfile.open(
            fileobj=changed, mode="w"
        ) as target:
            for member in source:
                data = source.extractfile(member) if member.isfile() else None
                if member.name.endswith("example.py"):
                    member.mode = 0o755
                target.addfile(member, data)
        payload = changed.getvalue()
        with self.assertRaises(ValueError):
            self.boundary.source_plan(payload, hashlib.sha256(payload).hexdigest())

    def test_rejects_manifest_runtime_and_untrusted_lock_source(self):
        manifest = (CONSUMER / "pyproject.toml").read_bytes().replace(
            b'requires-python = ">=3.12"', b'requires-python = ">=3.11"', 1
        )
        payload = snapshot({"pipeline/consumer/pyproject.toml": manifest})
        with self.assertRaisesRegex(ValueError, "manifest|runtime"):
            self.boundary.source_plan(payload, hashlib.sha256(payload).hexdigest())

        unsafe_lock = (CONSUMER / "uv.lock").read_bytes().replace(
            b"https://pypi.org/simple", b"https://packages.internal.invalid/simple", 1
        )
        payload = snapshot({"pipeline/consumer/uv.lock": unsafe_lock})
        with self.assertRaisesRegex(ValueError, "lock|registry|source"):
            self.boundary.source_plan(payload, hashlib.sha256(payload).hexdigest())

    def test_rejects_wrong_snapshot_identity(self):
        payload = snapshot()
        with self.assertRaisesRegex(ValueError, "identity|hash"):
            self.boundary.source_plan(payload, "0" * 64)


class ConsumerCiConfigTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not CI_CONFIG.is_file():
            raise AssertionError("Missing required implementation: .gitlab/ci/consumer.yml")
        cls.config = CI_CONFIG.read_text(encoding="utf-8")

    def test_mr_job_is_path_scoped_and_uses_fixed_isolated_commands(self):
        block = job_block(self.config, "consumer-mr-test")
        for expected in (
            "stage: test",
            "tags: [e106-mr-isolated]",
            "GIT_STRATEGY: none",
            "dependencies: []",
            "resource_group: consumer-mr-test",
            "interruptible: true",
            "timeout: 15m",
            '$CI_PIPELINE_SOURCE == "merge_request_event"',
            '$CI_MERGE_REQUEST_TARGET_BRANCH_NAME == "develop"',
            "- pipeline/consumer/**/*",
            "- .gitlab/ci/consumer.yml",
            "- infra/scripts/ci-consumer-mr-*.py",
            "- infra/scripts/test_ci_consumer_mr.py",
        ):
            self.assertIn(expected, block)
        scripts = [line.strip() for line in block.splitlines() if line.strip().startswith("- ")]
        self.assertEqual(
            scripts[:4],
            [
                "- cd pipeline/consumer",
                "- uv sync --frozen",
                "- uv run ruff check .",
                "- uv run pytest -v",
            ],
        )
        for forbidden in ("image:", "services:", "cache:", "artifacts:", "before_script:"):
            self.assertNotIn(forbidden, block)

    def test_image_job_is_protected_main_only_and_immutable(self):
        block = job_block(self.config, "consumer-image")
        for expected in (
            "stage: build",
            "tags: [e106-shell]",
            "needs: [consumer-test]",
            '$CI_COMMIT_BRANCH == "main"',
            '$CI_COMMIT_REF_PROTECTED == "true"',
            '$CI_PIPELINE_SOURCE == "push"',
            'CONSUMER_IMAGE="$DOCKERHUB_NAMESPACE/feelm-foldin-consumer:$CI_COMMIT_SHA"',
            'docker build --pull -t "$CONSUMER_IMAGE" ./pipeline/consumer',
            'docker push "$CONSUMER_IMAGE"',
        ):
            self.assertIn(expected, block)
        self.assertNotIn(":latest", block)

    def test_main_test_precedes_image_and_uses_locked_commands(self):
        block = job_block(self.config, "consumer-test")
        self.assertIn("stage: test", block)
        self.assertIn("tags: [e106-shell]", block)
        self.assertIn('CONSUMER_TEST_IMAGE="feelm-foldin-consumer-test:$CI_COMMIT_SHA"', block)
        self.assertIn('docker build --pull --target test -t "$CONSUMER_TEST_IMAGE"', block)
        self.assertIn('docker image rm "$CONSUMER_TEST_IMAGE"', block)
        self.assertIn('$CI_COMMIT_BRANCH == "main"', block)
        self.assertIn('$CI_COMMIT_REF_PROTECTED == "true"', block)
        self.assertIn('$CI_PIPELINE_SOURCE == "push"', block)


class ConsumerRunnerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.runner = load_required("test_consumer_runner", "ci-consumer-mr-runner.py")
        cls.configurator = load_required("test_consumer_config", "ci-consumer-mr-config.py")
        cls.preparer = load_required("test_consumer_prepare", "ci-consumer-mr-prepare.py")

    def config(self):
        return {
            "runner_user": "feelm-ci-test",
            "docker_host": "unix:///run/user/1001/docker.sock",
            "consumer_image": "feelm-consumer-ci:python3.12-uv0.12.5",
            "consumer_image_id": "sha256:" + "a" * 64,
        }

    def test_fixed_container_is_bounded_and_has_no_host_socket_or_secret(self):
        source = Path(self.runner.__file__).read_text(encoding="utf-8")
        for expected in (
            "uv sync --frozen",
            "uv run ruff check .",
            "uv run pytest -v",
            "'--network=bridge'",
            "'--read-only'",
            "'--cap-drop=ALL'",
            "'--security-opt=no-new-privileges'",
            "'--pull=never'",
            "'/work:rw,exec,nodev,nosuid,size=4g'",
            "'--property=KillMode=control-group'",
            " cleanup --container=",
        ):
            self.assertIn(expected, source)
        self.assertNotIn("/var/run/docker.sock", source)
        self.assertNotIn("--env-file", source)
        self.assertNotIn("CONSUMER_POSTGRES_DSN", source)
        self.assertNotIn("CONSUMER_REDIS_URL", source)

    def test_image_identity_is_checked_before_run_and_cleanup(self):
        config = self.config()
        inspect = subprocess.CompletedProcess([], 0, stdout=config["consumer_image_id"] + "\n")
        executed = subprocess.CompletedProcess([], 0)
        removed = subprocess.CompletedProcess([], 0)
        with tempfile.TemporaryDirectory() as directory, patch.object(
            self.runner.subprocess, "run", side_effect=[inspect, executed, removed]
        ) as run:
            source = Path(directory)
            (source / "pipeline" / "consumer").mkdir(parents=True)
            self.assertEqual(
                self.runner.workload(config, source, "ci486-consumer-0123456789ab"), 0
            )
        command = run.call_args_list[1].args[0]
        self.assertIn(config["consumer_image_id"], command)
        self.assertNotIn(config["consumer_image"], command)
        self.assertNotIn("--volume", command)
        self.assertEqual(
            run.call_args_list[2].args[0][-3:],
            ["rm", "--force", "ci486-consumer-0123456789ab"],
        )

    def test_config_and_runner_accept_same_fixed_image(self):
        image = "feelm-consumer-ci:python3.12-uv0.12.5"
        self.assertIsNotNone(re.fullmatch(self.configurator.IMAGE_PATTERN, image))
        config = self.config()
        with tempfile.NamedTemporaryFile(mode="w", delete=False) as output:
            import json

            json.dump(config, output)
            path = Path(output.name)
        try:
            with patch.object(self.runner.Path, "lstat") as lstat, patch.object(
                self.runner.Path, "read_text", return_value=__import__("json").dumps(config)
            ):
                lstat.return_value = type(
                    "Info",
                    (),
                    {"st_mode": 0o100600, "st_uid": 0, "st_nlink": 1, "st_size": path.stat().st_size},
                )()
                self.assertEqual(self.runner.read_config(path)["consumer_image"], image)
        finally:
            path.unlink(missing_ok=True)

    def test_preparer_builds_only_from_preloaded_pinned_inputs(self):
        source = Path(self.preparer.__file__).read_text(encoding="utf-8")
        self.assertIn("python:3.12.14-slim-bookworm@sha256:", self.preparer.BASE_IMAGE)
        self.assertEqual(self.preparer.UV_IMAGE, "ghcr.io/astral-sh/uv:0.12.5")
        self.assertEqual(self.preparer.TARGET_IMAGE,
                         "feelm-consumer-ci:python3.12-uv0.12.5")
        self.assertIn("'--pull=false'", source)
        self.assertIn("'--network=none'", source)
        self.assertNotIn("\n+ && uv --version", self.preparer.dockerfile())
        self.assertIn("docker', 'save'", source)
        self.assertIn("rootless_command('load')", source)


if __name__ == "__main__":
    unittest.main()
