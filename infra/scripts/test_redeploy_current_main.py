import os
import shutil
import subprocess
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
REDEPLOY = SCRIPT_DIR / "redeploy-current-main.sh"
SPARK = SCRIPT_DIR / "deploy-spark-code.sh"
MONITORING = SCRIPT_DIR / "deploy-monitoring.sh"


class RedeployCurrentMainTest(unittest.TestCase):
    @staticmethod
    def bash():
        if os.name != "nt":
            return shutil.which("bash")
        return str(Path(os.environ.get("ProgramFiles", r"C:\Program Files")) /
                   "Git" / "bin" / "bash.exe")

    def test_redeploy_requires_supported_component_before_docker_access(self):
        result = subprocess.run(
            [self.bash(), str(REDEPLOY), "unknown"], capture_output=True,
            text=True, encoding="utf-8", errors="replace",
        )
        self.assertEqual(result.returncode, 2)
        self.assertIn("Unsupported redeploy component", result.stderr)

    def test_running_images_must_resolve_to_immutable_healthy_digests(self):
        source = REDEPLOY.read_text(encoding="utf-8")
        for expected in (
            ".State.Status", ".State.Health.Status", ".RepoDigests",
            "@sha256:[0-9a-f]{64}", "docker image inspect \"${digest}\"",
            "--force-recreate", "config --quiet",
        ):
            self.assertIn(expected, source)
        self.assertNotIn("docker pull", source)

    def test_component_redeploys_use_existing_verified_deploy_paths(self):
        source = REDEPLOY.read_text(encoding="utf-8")
        for expected in (
            "deploy-backend.sh", "deploy-frontend.sh", "deploy-consumer.sh",
            "verify-datastores.sh", "deploy-monitoring.sh",
            "MONITORING_IMAGE_OVERRIDE_FILE", "DEPLOY_PIPELINE_SOURCE",
        ):
            self.assertIn(expected, source)

    def test_spark_redeploy_uses_manifest_and_rejects_pending_transaction(self):
        source = SPARK.read_text(encoding="utf-8")
        for expected in (
            "Pending Spark transaction must be resolved before redeploy",
            "state_field manifest revision", "state_field manifest image",
            "state_field manifest source_revision", "verify_running",
            "Spark last-known-good release is missing",
        ):
            self.assertIn(expected, source)

    def test_monitoring_accepts_only_an_existing_override_file(self):
        source = MONITORING.read_text(encoding="utf-8")
        self.assertIn('[[ -f "${MONITORING_IMAGE_OVERRIDE_FILE}" ]]', source)
        self.assertIn('compose+=(-f "${MONITORING_IMAGE_OVERRIDE_FILE}")', source)


if __name__ == "__main__":
    unittest.main()
