import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tarfile
import unittest


HERE = Path(__file__).resolve().parent


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


boundary = load("test_docs_boundary", "ci-docs-mr-boundary.py")
runner = load("test_docs_runner", "ci-docs-mr-runner.py")


def snapshot(files=None, tracked=None):
    files = files or {"README.md": b"[docs](docs/guide.md)\n", "docs/guide.md": b"ok\n"}
    tracked = tracked or sorted([*files, "src/main.py"])
    values = dict(files)
    values[boundary.MANIFEST] = json.dumps(tracked, ensure_ascii=False).encode("utf-8")
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w") as archive:
        for name, data in values.items():
            item = tarfile.TarInfo(name)
            item.size = len(data)
            item.mode = 0o644
            archive.addfile(item, io.BytesIO(data))
    return output.getvalue()


class DocsBoundaryTests(unittest.TestCase):
    def test_accepts_only_all_tracked_markdown_and_manifest(self):
        payload = snapshot()
        files, _ = boundary.source_plan(payload, hashlib.sha256(payload).hexdigest())
        self.assertEqual(set(files), {"README.md", "docs/guide.md", boundary.MANIFEST})

    def test_rejects_missing_markdown_extra_source_and_link(self):
        payload = snapshot(files={"README.md": b"ok\n"}, tracked=["README.md", "docs/missing.md"])
        with self.assertRaisesRegex(ValueError, "differs"):
            boundary.source_plan(payload, hashlib.sha256(payload).hexdigest())
        payload = snapshot(files={"README.md": b"ok\n", "src/main.py": b"print(1)"})
        with self.assertRaisesRegex(ValueError, "Unsupported"):
            boundary.source_plan(payload, hashlib.sha256(payload).hexdigest())
        output = io.BytesIO()
        with tarfile.open(fileobj=output, mode="w") as archive:
            item = tarfile.TarInfo("README.md")
            item.type = tarfile.SYMTYPE
            item.linkname = "target"
            archive.addfile(item)
        payload = output.getvalue()
        with self.assertRaises(ValueError):
            boundary.source_plan(payload, hashlib.sha256(payload).hexdigest())

    def test_runner_command_is_offline_unprivileged_and_fixed(self):
        command = runner.unit_command(Path("/run/ci296/source"), "ci296-owner-012345abcdef.service")
        joined = " ".join(command)
        for expected in (
            "User=feelm-ci-test", "PrivateNetwork=yes", "ProtectSystem=strict",
            "MemoryMax=512M", str(runner.INSTALLED_CHECKER), "tracked-paths",
        ):
            self.assertIn(expected, joined)
        self.assertNotIn("curl", joined)
        self.assertNotIn("sudo", joined)


if __name__ == "__main__":
    unittest.main()
