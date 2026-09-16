import importlib.util
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load_stage():
    spec = importlib.util.spec_from_file_location(
        "ci_server_pilot_stage", HERE / "ci-server-pilot-stage.py"
    )
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class ServerPilotStageTests(unittest.TestCase):
    def test_stage_keeps_all_mr_adapter_runtime_dependencies(self):
        stage = load_stage()
        required = {
            "ci-gitlab-adapter-update.py",
            "ci-frontend-mr-boundary.py",
            "ci-frontend-mr-config.py",
            "ci-frontend-mr-runner.py",
            "ci-pipeline-cache-policy.py",
            "ci-pipeline-mr-boundary.py",
            "ci-pipeline-mr-config.py",
            "ci-pipeline-mr-prepare.py",
            "ci-pipeline-mr-runner.py",
            "ci-consumer-mr-boundary.py",
            "ci-consumer-mr-config.py",
            "ci-consumer-mr-prepare.py",
            "ci-consumer-mr-runner.py",
        }

        self.assertEqual(set(), required.difference(stage.FILES))

    def test_staged_adapter_import_smoke_uses_only_uploaded_scripts(self):
        stage = load_stage()
        with tempfile.TemporaryDirectory() as temporary:
            scripts = Path(temporary) / "scripts"
            scripts.mkdir()
            for name in stage.FILES:
                source = HERE / name
                target = scripts / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)

            result = subprocess.run(
                [sys.executable, "-I", "-B", str(scripts / "ci-gitlab-job-adapter.py")],
                capture_output=True,
                text=True,
                timeout=30,
            )

        self.assertEqual(64, result.returncode, result.stderr)
        self.assertTrue(
            result.stderr.startswith("USAGE: ci-gitlab-job-adapter.py run"), result.stderr
        )


if __name__ == "__main__":
    unittest.main()
