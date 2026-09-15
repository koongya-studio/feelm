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
    @staticmethod
    def _bash_path(path: Path) -> str:
        value = path.as_posix()
        if os.name == "nt" and len(value) >= 3 and value[1:3] == ":/":
            return f"/{value[0].lower()}/{value[3:]}"
        return value

    def setUp(self):
        self.bash = os.environ.get("TEST_BASH") or shutil.which("bash")
        if self.bash is None:
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

            if [[ "$1" == "port" ]]; then
              if [[ "$2" == "feelm-postgres" ]]; then
                printf '%s:%s\n' "${ACTUAL_POSTGRES_BIND_ADDRESS:-${POSTGRES_BIND_ADDRESS:-127.0.0.1}}" "$3"
              else
                printf '%s:%s\n' "${ACTUAL_REDIS_BIND_ADDRESS:-${REDIS_BIND_ADDRESS:-127.0.0.1}}" "$3"
              fi
              exit 0
            fi

            if [[ "$1 $2" == "exec feelm-postgres" ]]; then
              if [[ "${FAIL_DATASTORE_VERIFY:-0}" == "1" ]]; then
                printf '0\n'
              else
                printf '1\n'
              fi
              exit 0
            fi

            if [[ "$1 $2" == "exec feelm-redis" ]]; then
              command="$*"
              if [[ "$command" == *"test -n"* ]]; then
                exit 0
              fi
              if [[ "$command" == *"ACL USERS"* ]]; then
                printf 'default\nfeelm_readonly\n'
                exit 0
              fi
              if [[ "$command" == *"ACL DRYRUN"* ]]; then
                if [[ "$command" == *" SET "* || "$command" == *" DEL "* ]]; then
                  printf 'User feelm_readonly has no permissions to run the command\n'
                else
                  printf 'OK\n'
                fi
                exit 0
              fi
              if [[ "$command" == *"--user feelm_readonly"* ]]; then
                exit 0
              fi
              if [[ "$command" == *"REDISCLI_AUTH"* ]]; then
                printf 'PONG\n'
              else
                printf 'NOAUTH Authentication required.\n'
              fi
              exit 0
            fi

            if [[ "$1 $2" == "exec feelm-backend" ]]; then
              shift 2
              "$DEPLOY_TEST_CURL" "$@"
              exit
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
        self._write_executable(
            "iptables",
            r"""
            #!/usr/bin/env bash
            set -eu
            printf 'iptables %s\n' "$*" >>"$DEPLOY_TEST_EVENTS"
            if [[ "$*" == "-S DOCKER-USER" && "${FAIL_FIREWALL_VERIFY:-0}" != "1" ]]; then
              printf '%s\n' "-A DOCKER-USER ! -s ${DISTRIBUTED_NODE_IP}/32 -p tcp -m multiport --dports 5432,6379 -j DROP"
              exit 0
            fi
            exit 1
            """,
        )

    def run_deploy(self, previous="registry/backend:previous", **behavior):
        self.state_file.write_text(previous, encoding="utf-8")
        candidate = "registry/backend:candidate"
        env = os.environ.copy()
        env.update(
            {
                "PATH": f"{self._bash_path(self.bin_dir)}:{env['PATH']}",
                "DEPLOY_TEST_STATE": self._bash_path(self.state_file),
                "DEPLOY_TEST_EVENTS": self._bash_path(self.events_file),
                "DEPLOY_TEST_CURL": self._bash_path(self.bin_dir / "curl"),
                "DEPLOY_TEST_CANDIDATE": candidate,
                "DEPLOY_TEST_PREVIOUS": previous,
                "DEPLOY_HEALTH_ATTEMPTS": "1",
            }
        )
        env.update({key: str(value) for key, value in behavior.items()})
        result = subprocess.run(
            [self.bash, self._bash_path(DEPLOY_SCRIPT), candidate],
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

    def test_datastore_verification_failure_restores_previous_image(self):
        result, current, _ = self.run_deploy(FAIL_DATASTORE_VERIFY=1)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(current, "registry/backend:previous")
        self.assertIn("PostgreSQL SELECT 1 verification failed", result.stderr)

    def test_remote_datastore_bindings_accept_configured_private_address(self):
        result, current, events = self.run_deploy(
            SERVICE_NODE_IP="192.168.10.10",
            DISTRIBUTED_NODE_IP="192.168.10.11",
            POSTGRES_BIND_ADDRESS="192.168.10.10",
            REDIS_BIND_ADDRESS="192.168.10.10",
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(current, "registry/backend:candidate")
        self.assertIn("iptables -S DOCKER-USER", events)

    def test_remote_datastore_binding_without_firewall_restores_previous_image(self):
        result, current, _ = self.run_deploy(
            SERVICE_NODE_IP="192.168.10.10",
            DISTRIBUTED_NODE_IP="192.168.10.11",
            POSTGRES_BIND_ADDRESS="192.168.10.10",
            REDIS_BIND_ADDRESS="192.168.10.10",
            FAIL_FIREWALL_VERIFY=1,
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(current, "registry/backend:previous")
        self.assertIn("Cannot read DOCKER-USER firewall rules", result.stderr)

    def test_mixed_datastore_bindings_restore_previous_image(self):
        result, current, _ = self.run_deploy(
            SERVICE_NODE_IP="192.168.10.10",
            DISTRIBUTED_NODE_IP="192.168.10.11",
            POSTGRES_BIND_ADDRESS="192.168.10.10",
            REDIS_BIND_ADDRESS="127.0.0.1",
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(current, "registry/backend:previous")
        self.assertIn("must both use loopback or both use", result.stderr)

    def test_public_remote_datastore_address_restores_previous_image(self):
        result, current, _ = self.run_deploy(
            SERVICE_NODE_IP="203.0.113.10",
            DISTRIBUTED_NODE_IP="192.168.10.11",
            POSTGRES_BIND_ADDRESS="203.0.113.10",
            REDIS_BIND_ADDRESS="203.0.113.10",
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(current, "registry/backend:previous")
        self.assertIn("SERVICE_NODE_IP must be an RFC1918 private IPv4 address", result.stderr)

    def test_unexpected_runtime_binding_restores_previous_image(self):
        result, current, _ = self.run_deploy(ACTUAL_POSTGRES_BIND_ADDRESS="0.0.0.0")

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(current, "registry/backend:previous")
        self.assertIn("does not match 127.0.0.1:5432", result.stderr)

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
