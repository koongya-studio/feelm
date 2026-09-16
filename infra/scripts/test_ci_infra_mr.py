import hashlib
import io
from pathlib import Path
import tarfile
import unittest


HERE = Path(__file__).resolve().parent


def load(name):
    import importlib.util
    import sys
    spec = importlib.util.spec_from_file_location(name, HERE / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


boundary = load("ci-infra-mr-boundary")
runner = load("ci-infra-mr-runner")


def archive(entries):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w") as stream:
        for name, data in entries.items():
            item = tarfile.TarInfo(name)
            item.mode = 0o600
            item.size = len(data)
            stream.addfile(item, io.BytesIO(data))
    return output.getvalue()


def archive_with_directories(entries):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w") as stream:
        for name in (".gitlab/", ".gitlab/ci/", "infra/", "infra/scripts/"):
            item = tarfile.TarInfo(name)
            item.type = tarfile.DIRTYPE
            item.mode = 0o700
            stream.addfile(item)
        for name, data in entries.items():
            item = tarfile.TarInfo(name)
            item.mode = 0o600
            item.size = len(data)
            stream.addfile(item, io.BytesIO(data))
    return output.getvalue()


class InfraMrTests(unittest.TestCase):
    def test_boundary_accepts_only_gitlab_and_infra_files(self):
        payload = archive({
            ".gitlab-ci.yml": b"include:\n",
            ".gitlab/ci/infra.yml": b"infra-config-test:\n",
            "infra/scripts/run-infra-config-tests.py": b"import unittest\n",
            "infra/scripts/test_ci_infra_mr.py": b"import unittest\n",
        })
        files, _ = boundary.source_plan(payload, hashlib.sha256(payload).hexdigest())
        self.assertEqual(set(files), {
            ".gitlab-ci.yml", ".gitlab/ci/infra.yml", "infra/scripts/run-infra-config-tests.py",
            "infra/scripts/test_ci_infra_mr.py"})

        rejected = archive({
            ".gitlab-ci.yml": b"include:\n",
            ".gitlab/ci/infra.yml": b"job:\n",
            "infra/scripts/run-infra-config-tests.py": b"test\n",
            "infra/scripts/test_ci_infra_mr.py": b"test\n",
            "backend/.env": b"SECRET=value\n",
        })
        with self.assertRaisesRegex(ValueError, "out-of-scope"):
            boundary.source_plan(rejected, hashlib.sha256(rejected).hexdigest())

    def test_runner_is_networkless_and_has_fixed_test_set(self):
        command = runner.unit_command(Path("/run/source"), "ci490-infra-0123456789ab.service")
        self.assertIn("--property=PrivateNetwork=yes", command)
        self.assertIn("--property=ProtectSystem=strict", command)
        self.assertIn(runner.FIXED_COMMAND, command)

    def test_boundary_accepts_normalized_archive_directories(self):
        payload = archive_with_directories({
            ".gitlab-ci.yml": b"include:\n",
            ".gitlab/ci/infra.yml": b"infra-config-test:\n",
            "infra/scripts/run-infra-config-tests.py": b"import unittest\n",
            "infra/scripts/test_ci_infra_mr.py": b"import unittest\n",
        })
        files, directories = boundary.source_plan(
            payload, hashlib.sha256(payload).hexdigest())
        self.assertIn(".gitlab", directories)
        self.assertIn("infra", directories)
        self.assertIn(".gitlab-ci.yml", files)


if __name__ == "__main__":
    unittest.main()
