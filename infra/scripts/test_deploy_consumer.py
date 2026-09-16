import os
import shutil
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
DEPLOY_SCRIPT = SCRIPT_DIR / "deploy-consumer.sh"
ROLLBACK_SCRIPT = SCRIPT_DIR / "rollback-consumer.sh"


class DeployConsumerTest(unittest.TestCase):
    candidate = "registry/consumer:candidate"
    previous = "registry/consumer:previous"

    @staticmethod
    def _bash_path(path: Path) -> str:
        value = path.as_posix()
        if os.name == "nt" and len(value) >= 3 and value[1:3] == ":/":
            return f"/{value[0].lower()}/{value[3:]}"
        return value

    def setUp(self) -> None:
        self.bash = os.environ.get("TEST_BASH") or shutil.which("bash")
        if self.bash is None:
            self.skipTest("a POSIX bash environment is required")

        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.bin_dir = self.root / "bin"
        self.bin_dir.mkdir()
        self.state_file = self.root / "current-image"
        self.deploy_state_file = self.root / "consumer-last-known-good"
        self.group_file = self.root / "consumer-id"
        self.events_file = self.root / "events"
        self.env_file = self.root / "infra.env"
        self.env_file.write_text(
            "CONSUMER_KAFKA_TOPIC=feelm.rating.v1\n"
            "CONSUMER_KAFKA_GROUP_ID=feelm-foldin-consumer-v1\n",
            encoding="utf-8",
        )
        self._write_fake_docker()

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def _write_executable(self, name: str, content: str) -> None:
        path = self.bin_dir / name
        path.write_text(textwrap.dedent(content).lstrip(), encoding="utf-8")
        path.chmod(0o755)

    def _write_fake_docker(self) -> None:
        self._write_executable(
            "docker",
            r"""
            #!/usr/bin/env bash
            set -eu
            printf 'docker %s\n' "$*" >>"$DEPLOY_TEST_EVENTS"

            if [[ "$1" == "inspect" ]]; then
              if [[ "$2" == "--format" && "$3" == *Config.Image* ]]; then
                [[ -s "$DEPLOY_TEST_STATE" ]] || exit 1
                cat "$DEPLOY_TEST_STATE"
                exit 0
              fi
              if [[ "$2" == "--format" && "$3" == *State.Status* ]]; then
                image="$(cat "$DEPLOY_TEST_STATE" 2>/dev/null || true)"
                if [[ "$image" == "$DEPLOY_TEST_CANDIDATE" && "${FAIL_CANDIDATE_RUNTIME:-0}" == "1" ]]; then
                  printf 'exited 1\n'
                elif [[ "$image" == "$DEPLOY_TEST_PREVIOUS" && "${FAIL_ROLLBACK_RUNTIME:-0}" == "1" ]]; then
                  printf 'exited 1\n'
                elif [[ -n "$image" ]]; then
                  printf 'running 0\n'
                else
                  exit 1
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
              if [[ "$*" == *" config --quiet"* ]]; then
                [[ "${FAIL_CONFIG:-0}" != "1" ]]
                exit
              fi
              image="${CONSUMER_IMAGE:?}"
              if [[ "$image" == "$DEPLOY_TEST_CANDIDATE" && "${FAIL_CANDIDATE_UP:-0}" == "1" ]]; then
                if [[ "${CANDIDATE_REPLACED_BEFORE_FAILURE:-0}" == "1" ]]; then
                  printf '%s' "$image" >"$DEPLOY_TEST_STATE"
                  printf 'candidate-id' >"$DEPLOY_TEST_GROUP"
                fi
                exit 1
              fi
              if [[ "$image" == "$DEPLOY_TEST_PREVIOUS" && "${FAIL_ROLLBACK_UP:-0}" == "1" ]]; then
                exit 1
              fi
              printf '%s' "$image" >"$DEPLOY_TEST_STATE"
              if [[ "$image" == "$DEPLOY_TEST_CANDIDATE" ]]; then
                printf 'candidate-id' >"$DEPLOY_TEST_GROUP"
              else
                printf 'rollback-id' >"$DEPLOY_TEST_GROUP"
              fi
              exit 0
            fi

            if [[ "$1 $2" == "exec feelm-kafka" ]]; then
              id="$(cat "$DEPLOY_TEST_GROUP" 2>/dev/null || true)"
              if [[ "${FAIL_ASSIGNMENT:-0}" == "1" || -z "$id" ]]; then
                id='-'
              fi
              cat <<EOF
GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID
feelm-foldin-consumer-v1 feelm.rating.v1 0 1 1 0 $id /host client
EOF
              exit 0
            fi

            if [[ "$1" == "logs" || "$1" == "stop" ]]; then
              if [[ "$1" == "stop" ]]; then
                : >"$DEPLOY_TEST_STATE"
                : >"$DEPLOY_TEST_GROUP"
              fi
              exit 0
            fi

            exit 1
            """,
        )

    def _environment(self, **overrides: object) -> dict[str, str]:
        environment = os.environ.copy()
        environment.update(
            {
                "PATH": f"{self.bin_dir}{os.pathsep}{environment.get('PATH', '')}",
                "COMPOSE_ENV_FILE": self._bash_path(self.env_file),
                "CONSUMER_DEPLOY_STATE_FILE": self._bash_path(self.deploy_state_file),
                "CONSUMER_DEPLOY_ATTEMPTS": "2",
                "CONSUMER_DEPLOY_INTERVAL_SECONDS": "0",
                "DEPLOY_TEST_STATE": self._bash_path(self.state_file),
                "DEPLOY_TEST_GROUP": self._bash_path(self.group_file),
                "DEPLOY_TEST_EVENTS": self._bash_path(self.events_file),
                "DEPLOY_TEST_CANDIDATE": self.candidate,
                "DEPLOY_TEST_PREVIOUS": self.previous,
            }
        )
        environment.update({key: str(value) for key, value in overrides.items()})
        return environment

    def _run(self, script: Path, image: str, **overrides: object) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [self.bash, self._bash_path(script), image],
            cwd=SCRIPT_DIR.parents[1],
            env=self._environment(**overrides),
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            check=False,
        )

    def run_deploy(
        self, *, previous: str | None = None, persisted: bool = True, **overrides: object
    ) -> tuple[subprocess.CompletedProcess[str], str, str]:
        if previous is None:
            previous = self.previous
        self.state_file.write_text(previous, encoding="utf-8")
        self.group_file.write_text("previous-id" if previous else "", encoding="utf-8")
        if previous and persisted:
            self.deploy_state_file.write_bytes((previous + "\n").encode())
        result = self._run(DEPLOY_SCRIPT, self.candidate, **overrides)
        current = self.state_file.read_text(encoding="utf-8")
        events = self.events_file.read_text(encoding="utf-8")
        return result, current, events

    def assert_no_compose_build(self, events: str) -> None:
        for command in events.splitlines():
            arguments = command.split()
            if arguments[:2] == ["docker", "compose"]:
                self.assertNotIn("build", arguments, command)
                self.assertNotIn("--build", arguments, command)

    def test_success_replaces_consumer_after_new_group_assignment(self) -> None:
        result, current, events = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(current, self.candidate)
        self.assertEqual(self.deploy_state_file.read_text(encoding="utf-8").strip(), self.candidate)
        self.assertIn("Consumer deployment completed", result.stdout)
        self.assertIn("force-recreate consumer", events)
        self.assertIn(f"docker pull {self.candidate}", events)
        self.assert_no_compose_build(events)

    def test_compose_build_detection_ignores_runner_checkout_path(self) -> None:
        checkout = "/home/gitlab-runner/builds/runner/project"

        self.assert_no_compose_build(
            f"docker compose -f {checkout}/infra/compose/distributed.yml config --quiet\n"
        )
        with self.assertRaises(AssertionError):
            self.assert_no_compose_build(
                f"docker compose -f {checkout}/infra/compose/distributed.yml build consumer\n"
            )

    def test_initialization_records_only_a_healthy_running_image(self) -> None:
        self.state_file.write_text(self.previous, encoding="utf-8")
        self.group_file.write_text("previous-id", encoding="utf-8")

        result = self._run(DEPLOY_SCRIPT, "--initialize-state")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.state_file.read_text(encoding="utf-8"), self.previous)
        self.assertEqual(self.deploy_state_file.read_text(encoding="utf-8").strip(), self.previous)
        self.assertIn("deployment state initialized", result.stdout)

    def test_pull_failure_keeps_running_consumer(self) -> None:
        result, current, events = self.run_deploy(FAIL_PULL=1)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(current, self.previous)
        self.assertNotIn("force-recreate consumer", events)

    def test_invalid_compose_keeps_running_consumer(self) -> None:
        result, current, events = self.run_deploy(FAIL_CONFIG=1)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(current, self.previous)
        self.assertNotIn("force-recreate consumer", events)

    def test_compose_failure_restores_previous_image(self) -> None:
        result, current, _ = self.run_deploy(
            FAIL_CANDIDATE_UP=1,
            CANDIDATE_REPLACED_BEFORE_FAILURE=1,
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(current, self.previous)
        self.assertIn("previous Consumer image was restored", result.stderr)

    def test_runtime_failure_restores_previous_image(self) -> None:
        result, current, _ = self.run_deploy(FAIL_CANDIDATE_RUNTIME=1)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(current, self.previous)
        self.assertEqual(self.deploy_state_file.read_text(encoding="utf-8").strip(), self.previous)
        self.assertIn("runtime verification failed", result.stderr)

    def test_missing_kafka_assignment_restores_previous_image(self) -> None:
        result, current, _ = self.run_deploy(FAIL_ASSIGNMENT=1)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(current, self.previous)
        self.assertIn("runtime verification failed", result.stderr)

    def test_deploy_without_initialized_state_fails_before_mutation(self) -> None:
        result, current, events = self.run_deploy(persisted=False)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(current, self.previous)
        self.assertIn("deployment state is not initialized", result.stderr)
        self.assertNotIn("force-recreate consumer", events)
        self.assertNotIn(f"docker pull {self.candidate}", events)

    def test_rollback_failure_requires_manual_recovery(self) -> None:
        result, current, _ = self.run_deploy(
            FAIL_CANDIDATE_RUNTIME=1,
            FAIL_ROLLBACK_RUNTIME=1,
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(current, self.previous)
        self.assertIn("manual recovery is required", result.stderr)

    def test_direct_rollback_replaces_candidate_and_verifies_new_assignment(self) -> None:
        self.state_file.write_text(self.candidate, encoding="utf-8")
        self.group_file.write_text("candidate-id", encoding="utf-8")

        result = self._run(ROLLBACK_SCRIPT, self.previous)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.state_file.read_text(encoding="utf-8"), self.previous)
        self.assertEqual(self.deploy_state_file.read_text(encoding="utf-8").strip(), self.previous)
        self.assertIn("Consumer rollback completed", result.stdout)

    def test_retry_uses_persisted_last_known_good_not_current_failed_candidate(self) -> None:
        self.state_file.write_text(self.candidate, encoding="utf-8")
        self.group_file.write_text("candidate-id", encoding="utf-8")
        self.deploy_state_file.write_bytes((self.previous + "\n").encode())

        result = self._run(DEPLOY_SCRIPT, self.candidate, FAIL_CANDIDATE_RUNTIME=1)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.state_file.read_text(encoding="utf-8"), self.previous)
        self.assertEqual(self.deploy_state_file.read_text(encoding="utf-8").strip(), self.previous)
        self.assertIn(f"Restoring previous Consumer image: {self.previous}", result.stderr)

    def test_retry_reconciles_interrupted_candidate_before_candidate_pull(self) -> None:
        self.state_file.write_text(self.candidate, encoding="utf-8")
        self.group_file.write_text("candidate-id", encoding="utf-8")
        self.deploy_state_file.write_bytes((self.previous + "\n").encode())

        result = self._run(DEPLOY_SCRIPT, self.candidate, FAIL_PULL=1)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.state_file.read_text(encoding="utf-8"), self.previous)
        self.assertEqual(self.deploy_state_file.read_text(encoding="utf-8").strip(), self.previous)
        events = self.events_file.read_text(encoding="utf-8")
        rollback_mutation = events.index("force-recreate consumer")
        candidate_pull = events.index(f"docker pull {self.candidate}")
        self.assertLess(rollback_mutation, candidate_pull)
        self.assertIn("reconciling before deployment", result.stderr)

    def test_failure_does_not_copy_container_logs_to_ci_output(self) -> None:
        result, _, events = self.run_deploy(FAIL_CANDIDATE_RUNTIME=1)

        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("docker logs", events)
        self.assertIn("logs remain on the distributed host", result.stderr)

    def test_ci_commit_guard_wraps_the_compose_mutation(self) -> None:
        source = DEPLOY_SCRIPT.read_text(encoding="utf-8")
        mutation = source.index('"${compose[@]}" up -d --no-deps --force-recreate consumer')
        guards = [index for index in range(len(source)) if source.startswith("verify_current_commit", index)]

        self.assertGreaterEqual(len(guards), 3)
        self.assertTrue(any(index < mutation for index in guards[1:]))
        self.assertTrue(any(index > mutation for index in guards[1:]))

    def test_missing_environment_file_fails_before_docker(self) -> None:
        missing = self.root / "missing.env"
        result = self._run(
            DEPLOY_SCRIPT,
            self.candidate,
            COMPOSE_ENV_FILE=self._bash_path(missing),
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("environment file does not exist", result.stderr)
        self.assertFalse(self.events_file.exists())


if __name__ == "__main__":
    unittest.main()
