import os
import shutil
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
DEPLOY_SCRIPT = SCRIPT_DIR / "deploy-frontend.sh"
ROLLBACK_SCRIPT = SCRIPT_DIR / "rollback-frontend.sh"


class DeployFrontendTest(unittest.TestCase):
    def setUp(self):
        self.bash = self._find_bash()
        if self.bash is None:
            self.skipTest("a POSIX bash environment is required")

        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.bin_dir = self.root / "bin"
        self.bin_dir.mkdir()
        self.state_file = self.root / "current-image"
        self.events_file = self.root / "events"
        self.curl_attempts_file = self.root / "curl-attempts"
        self._write_fake_commands()

    @staticmethod
    def _find_bash():
        if os.name != "nt":
            return shutil.which("bash")

        program_files = os.environ.get("ProgramFiles", r"C:\Program Files")
        git_bash = Path(program_files) / "Git" / "bin" / "bash.exe"
        return str(git_bash) if git_bash.is_file() else None

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
                target="${!#}"
                if [[ "$target" == "feelm-backend" ]]; then
                  [[ "${FAIL_BACKEND_IMAGE_LOOKUP:-0}" != "1" ]] || exit 1
                  printf '%s' "${DEPLOY_TEST_BACKEND_IMAGE:-}"
                  exit 0
                fi
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
              printf 'compose-backend-image %s\n' "${BACKEND_IMAGE:-missing}" >>"$DEPLOY_TEST_EVENTS"
              : "${BACKEND_IMAGE:?}"
              args=" $* "
              if [[ "$args" == *" run "* ]]; then
                [[ "${FAIL_NGINX_CONFIG:-0}" != "1" ]]
                exit
              fi
              if [[ "$args" == *" up "* && "$args" == *" frontend "* ]]; then
                image="${FRONTEND_IMAGE:?}"
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
              if [[ "$args" == *" up "* && "$args" == *" nginx "* ]]; then
                [[ "${FAIL_NGINX_UP:-0}" != "1" ]]
                exit
              fi
            fi

            if [[ "$1" == "logs" ]]; then
              printf 'fake frontend logs\n' >&2
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
            if [[ "$image" == "$DEPLOY_TEST_CANDIDATE" && "${FAIL_PUBLIC_HEALTH:-0}" == "1" ]]; then
              exit 1
            fi
            if [[ "$image" == "$DEPLOY_TEST_PREVIOUS" && "${FAIL_ROLLBACK_PUBLIC_HEALTH:-0}" == "1" ]]; then
              exit 1
            fi
            [[ -n "$image" ]]
            url="${!#}"
            if [[ "$url" == */healthz ]]; then
              attempts="$(cat "$DEPLOY_TEST_CURL_ATTEMPTS" 2>/dev/null || printf '0')"
              attempts="$((attempts + 1))"
              printf '%s' "$attempts" >"$DEPLOY_TEST_CURL_ATTEMPTS"
              if ((attempts <= ${PUBLIC_HEALTH_FAILURES_BEFORE_SUCCESS:-0})); then
                exit 1
              fi
              printf 'ok\n'
            else
              printf '<!doctype html>\n'
            fi
            """,
        )
        self._write_executable("sleep", "#!/usr/bin/env bash\nexit 0\n")

    def run_deploy(self, previous="registry/frontend:previous", **behavior):
        self.state_file.write_text(previous, encoding="utf-8")
        candidate = "registry/frontend:candidate"
        env = os.environ.copy()
        env.pop("BACKEND_IMAGE", None)
        env.update(
            {
                "PATH": f"{self.bin_dir.as_posix()}:{env['PATH']}",
                "DEPLOY_TEST_STATE": str(self.state_file),
                "DEPLOY_TEST_EVENTS": str(self.events_file),
                "DEPLOY_TEST_CANDIDATE": candidate,
                "DEPLOY_TEST_PREVIOUS": previous,
                "DEPLOY_TEST_BACKEND_IMAGE": "registry/backend:running",
                "DEPLOY_TEST_CURL_ATTEMPTS": str(self.curl_attempts_file),
                "DEPLOY_HEALTH_ATTEMPTS": "1",
                "FRONTEND_PUBLIC_HEALTH_ATTEMPTS": "3",
                "FRONTEND_PUBLIC_HEALTH_INTERVAL_SECONDS": "0",
                "FRONTEND_PUBLIC_HEALTH_REQUEST_TIMEOUT_SECONDS": "1",
                "FRONTEND_PUBLIC_BASE_URL": "https://example.test/app/",
                "CURL_BIN": str(self.bin_dir / "curl"),
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

    def run_rollback(self, **behavior):
        current_image = "registry/frontend:current"
        target_image = "registry/frontend:rollback"
        self.state_file.write_text(current_image, encoding="utf-8")
        env = os.environ.copy()
        env.pop("BACKEND_IMAGE", None)
        env.update(
            {
                "PATH": f"{self.bin_dir.as_posix()}:{env['PATH']}",
                "DEPLOY_TEST_STATE": str(self.state_file),
                "DEPLOY_TEST_EVENTS": str(self.events_file),
                "DEPLOY_TEST_CANDIDATE": target_image,
                "DEPLOY_TEST_PREVIOUS": current_image,
                "DEPLOY_TEST_BACKEND_IMAGE": "registry/backend:running",
                "DEPLOY_TEST_CURL_ATTEMPTS": str(self.curl_attempts_file),
                "DEPLOY_HEALTH_ATTEMPTS": "1",
                "FRONTEND_PUBLIC_HEALTH_ATTEMPTS": "3",
                "FRONTEND_PUBLIC_HEALTH_INTERVAL_SECONDS": "0",
                "FRONTEND_PUBLIC_HEALTH_REQUEST_TIMEOUT_SECONDS": "1",
                "FRONTEND_PUBLIC_BASE_URL": "https://example.test/app/",
                "CURL_BIN": str(self.bin_dir / "curl"),
            }
        )
        env.update({key: str(value) for key, value in behavior.items()})
        result = subprocess.run(
            [self.bash, str(ROLLBACK_SCRIPT), target_image],
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
        result, current, events = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(current, "registry/frontend:candidate")
        self.assertIn("nginx nginx -t", events)
        self.assertIn("compose-backend-image registry/backend:running", events)
        self.assertIn("--connect-timeout 1 --max-time 1", events)
        self.assertIn("Deployment completed", result.stdout)

    def test_transient_public_failure_succeeds_without_rollback(self):
        result, current, events = self.run_deploy(PUBLIC_HEALTH_FAILURES_BEFORE_SUCCESS=2)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(current, "registry/frontend:candidate")
        self.assertEqual(events.count("app/healthz"), 3)
        self.assertEqual(events.count("force-recreate nginx"), 1)
        self.assertIn("Public frontend is not ready; retrying", result.stderr)

    def test_explicit_backend_image_is_used_without_container_lookup(self):
        result, current, events = self.run_deploy(
            BACKEND_IMAGE="registry/backend:explicit",
            FAIL_BACKEND_IMAGE_LOOKUP=1,
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(current, "registry/frontend:candidate")
        self.assertIn("compose-backend-image registry/backend:explicit", events)
        self.assertNotIn("feelm-backend", events)

    def test_missing_backend_image_fails_before_candidate_change(self):
        result, current, events = self.run_deploy(
            DEPLOY_TEST_BACKEND_IMAGE="",
            FAIL_BACKEND_IMAGE_LOOKUP=1,
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(current, "registry/frontend:previous")
        self.assertIn("running feelm-backend image could not be determined", result.stderr)
        self.assertNotIn("docker compose", events)
        self.assertNotIn("docker pull", events)

    def test_pull_failure_keeps_running_service(self):
        result, current, events = self.run_deploy(PULL_REQUIRED=1, FAIL_PULL=1)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(current, "registry/frontend:previous")
        self.assertNotIn("docker compose", events)

    def test_compose_failure_restores_previous_image(self):
        result, current, _ = self.run_deploy(
            FAIL_CANDIDATE_UP=1,
            CANDIDATE_REPLACED_BEFORE_FAILURE=1,
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(current, "registry/frontend:previous")
        self.assertIn("Candidate deployment command failed", result.stderr)

    def test_health_failure_restores_previous_image(self):
        result, current, _ = self.run_deploy(FAIL_CANDIDATE_HEALTH=1)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(current, "registry/frontend:previous")
        self.assertIn("previous image was restored", result.stderr)

    def test_nginx_config_failure_restores_without_replacing_nginx(self):
        result, current, events = self.run_deploy(FAIL_NGINX_CONFIG=1)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(current, "registry/frontend:previous")
        self.assertEqual(events.count("force-recreate nginx"), 0)

    def test_public_health_failure_restores_previous_image(self):
        result, current, events = self.run_deploy(FAIL_PUBLIC_HEALTH=1)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(current, "registry/frontend:previous")
        self.assertGreaterEqual(events.count("force-recreate nginx"), 2)

    def test_public_and_rollback_health_failure_requires_manual_recovery(self):
        result, current, events = self.run_deploy(
            FAIL_PUBLIC_HEALTH=1,
            FAIL_ROLLBACK_PUBLIC_HEALTH=1,
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(current, "registry/frontend:previous")
        self.assertEqual(events.count("app/healthz"), 6)
        self.assertIn("Rollback failed; manual recovery is required", result.stderr)

    def test_first_deployment_failure_stops_failed_candidate(self):
        result, current, events = self.run_deploy(previous="", FAIL_CANDIDATE_HEALTH=1)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(current, "")
        self.assertIn("No previous image is available for rollback", result.stderr)
        self.assertIn("docker stop feelm-frontend", events)

    def test_rollback_uses_running_backend_image(self):
        result, current, events = self.run_rollback()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(current, "registry/frontend:rollback")
        self.assertIn("compose-backend-image registry/backend:running", events)
        self.assertIn("Rollback completed", result.stdout)

    def test_rollback_retries_transient_public_failure(self):
        result, current, events = self.run_rollback(PUBLIC_HEALTH_FAILURES_BEFORE_SUCCESS=2)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(current, "registry/frontend:rollback")
        self.assertEqual(events.count("app/healthz"), 3)
        self.assertIn("Rollback public frontend is not ready; retrying", result.stderr)

    def test_rollback_public_failure_stops_after_attempt_limit(self):
        result, current, events = self.run_rollback(FAIL_PUBLIC_HEALTH=1)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(current, "registry/frontend:rollback")
        self.assertEqual(events.count("app/healthz"), 3)
        self.assertIn("failed after all attempts", result.stderr)

    def test_rollback_missing_backend_image_fails_before_frontend_change(self):
        result, current, events = self.run_rollback(
            DEPLOY_TEST_BACKEND_IMAGE="",
            FAIL_BACKEND_IMAGE_LOOKUP=1,
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(current, "registry/frontend:current")
        self.assertIn("running feelm-backend image could not be determined", result.stderr)
        self.assertNotIn("docker compose", events)
        self.assertNotIn("docker pull", events)


if __name__ == "__main__":
    unittest.main()
