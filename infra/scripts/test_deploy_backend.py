import os
import shutil
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
DEPLOY_SCRIPT = SCRIPT_DIR / "deploy-backend.sh"


class DeployBackendTest(unittest.TestCase):
    def setUp(self):
        self.bash = shutil.which("bash")
        if self.bash is None or os.name == "nt":
            self.skipTest("a POSIX bash environment is required")

        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.bin_dir = self.root / "bin"
        self.bin_dir.mkdir()
        self.state_file = self.root / "current-image"
        self.events_file = self.root / "events"
        self._write_fake_commands()

    def tearDown(self):
        self.temp_dir.cleanup()

    def _write_executable(self, name, content):
        path = self.bin_dir / name
        path.write_text(textwrap.dedent(content).lstrip(), encoding="utf-8")
        path.chmod(0o755)

    def _write_fake_commands(self):
        self._write_executable(
            "docker",
            r"""
            #!/usr/bin/env bash
            set -eu
            printf 'docker %s\n' "$*" >>"$DEPLOY_TEST_EVENTS"

            if [[ "$1" == "inspect" ]]; then
              if [[ "$2" == "--format" && "$3" == *Config.Image* ]]; then
                [[ -s "$DEPLOY_TEST_STATE" ]] && cat "$DEPLOY_TEST_STATE"
                exit 0
              fi
              if [[ "$2" == "--format" && "$3" == *State.Health* ]]; then
                image="$(cat "$DEPLOY_TEST_STATE" 2>/dev/null || true)"
                if [[ "$image" == "$DEPLOY_TEST_CANDIDATE" && "${FAIL_CANDIDATE_HEALTH:-0}" == "1" ]]; then
                  printf 'unhealthy\n'
                elif [[ "$image" == "$DEPLOY_TEST_PREVIOUS" && "${FAIL_ROLLBACK_HEALTH:-0}" == "1" ]]; then
                  printf 'unhealthy\n'
                elif [[ -n "$image" ]]; then
                  printf 'healthy\n'
                else
                  printf 'none\n'
                fi
                exit 0
              fi
            fi

            if [[ "$1 $2" == "image inspect" ]]; then
              [[ "${PULL_REQUIRED:-0}" != "1" ]]
              exit
            fi

            if [[ "$1" == "pull" ]]; then
              [[ "${FAIL_PULL:-0}" != "1" ]]
              exit
            fi

            if [[ "$1" == "compose" ]]; then
              image="${BACKEND_IMAGE:?}"
              if [[ "$image" == "$DEPLOY_TEST_CANDIDATE" && "${FAIL_CANDIDATE_UP:-0}" == "1" ]]; then
                if [[ "${CANDIDATE_REPLACED_BEFORE_FAILURE:-0}" == "1" ]]; then
                  printf '%s' "$image" >"$DEPLOY_TEST_STATE"
                fi
                exit 1
              fi
              if [[ "$image" == "$DEPLOY_TEST_PREVIOUS" && "${FAIL_ROLLBACK_UP:-0}" == "1" ]]; then
                exit 1
              fi
              printf '%s' "$image" >"$DEPLOY_TEST_STATE"
              exit 0
            fi

            if [[ "$1" == "logs" ]]; then
              printf 'fake backend logs\n' >&2
              exit 0
            fi

            if [[ "$1" == "stop" ]]; then
              : >"$DEPLOY_TEST_STATE"
              exit 0
            fi

            exit 1
            """,
        )
        self._write_executable(
            "curl",
            r"""
            #!/usr/bin/env bash
            set -eu
            printf 'curl %s\n' "$*" >>"$DEPLOY_TEST_EVENTS"
            image="$(cat "$DEPLOY_TEST_STATE" 2>/dev/null || true)"
            if [[ "$image" == "$DEPLOY_TEST_CANDIDATE" && "${FAIL_CANDIDATE_CURL:-0}" == "1" ]]; then
              exit 1
            fi
            if [[ "$image" == "$DEPLOY_TEST_PREVIOUS" && "${FAIL_ROLLBACK_CURL:-0}" == "1" ]]; then
              exit 1
            fi
            [[ -n "$image" ]]
            """,
        )
        self._write_executable("sleep", "#!/usr/bin/env bash\nexit 0\n")

    def run_deploy(self, previous="registry/backend:previous", **behavior):
        self.state_file.write_text(previous, encoding="utf-8")
        candidate = "registry/backend:candidate"
        env = os.environ.copy()
        env.update(
            {
                "PATH": f"{self.bin_dir.as_posix()}:{env['PATH']}",
                "DEPLOY_TEST_STATE": str(self.state_file),
                "DEPLOY_TEST_EVENTS": str(self.events_file),
                "DEPLOY_TEST_CANDIDATE": candidate,
                "DEPLOY_TEST_PREVIOUS": previous,
                "DEPLOY_HEALTH_ATTEMPTS": "1",
            }
        )
        env.update({key: str(value) for key, value in behavior.items()})
        result = subprocess.run(
            [self.bash, str(DEPLOY_SCRIPT), candidate],
            cwd=SCRIPT_DIR.parent.parent,
            env=env,
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            check=False,
        )
        events = self.events_file.read_text(encoding="utf-8") if self.events_file.exists() else ""
        current = self.state_file.read_text(encoding="utf-8")
        return result, current, events

    def test_successful_deployment_uses_candidate_image(self):
        result, current, _ = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(current, "registry/backend:candidate")
        self.assertIn("Deployment completed", result.stdout)

    def test_compose_failure_restores_previous_image(self):
        result, current, events = self.run_deploy(
            FAIL_CANDIDATE_UP=1,
            CANDIDATE_REPLACED_BEFORE_FAILURE=1,
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(current, "registry/backend:previous")
        self.assertIn("Candidate deployment command failed", result.stderr)
        self.assertIn("Rollback completed", result.stdout)
        self.assertGreaterEqual(events.count("docker compose"), 2)

    def test_health_failure_restores_previous_image(self):
        result, current, _ = self.run_deploy(FAIL_CANDIDATE_HEALTH=1)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(current, "registry/backend:previous")
        self.assertIn("Candidate deployment failed; previous image was restored", result.stderr)

    def test_first_deployment_failure_stops_failed_candidate(self):
        result, current, events = self.run_deploy(previous="", FAIL_CANDIDATE_HEALTH=1)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(current, "")
        self.assertIn("No previous image is available for rollback", result.stderr)
        self.assertIn("docker stop feelm-backend", events)

    def test_rollback_failure_is_reported(self):
        result, current, _ = self.run_deploy(
            FAIL_CANDIDATE_HEALTH=1,
            FAIL_ROLLBACK_UP=1,
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(current, "registry/backend:candidate")
        self.assertIn("Rollback failed", result.stderr)

    def test_pull_failure_keeps_running_service(self):
        result, current, events = self.run_deploy(PULL_REQUIRED=1, FAIL_PULL=1)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(current, "registry/backend:previous")
        self.assertNotIn("docker compose", events)


if __name__ == "__main__":
    unittest.main()
