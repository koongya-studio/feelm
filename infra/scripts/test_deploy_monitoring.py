import os
import shutil
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
DEPLOY_SCRIPT = SCRIPT_DIR / "deploy-monitoring.sh"
MONITORING_COMPOSE = SCRIPT_DIR.parent / "compose" / "monitoring.yml"
CONTAINERS = (
    "feelm-prometheus",
    "feelm-grafana",
    "feelm-mattermost-alert-relay",
    "feelm-node-exporter",
    "feelm-postgres-exporter",
    "feelm-redis-exporter",
)


class DeployMonitoringTest(unittest.TestCase):
    def setUp(self):
        self.bash = self._find_bash()
        if self.bash is None:
            self.skipTest("a POSIX bash environment is required")
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.bin_dir = self.root / "bin"
        self.state_dir = self.root / "state"
        self.bin_dir.mkdir()
        self.state_dir.mkdir()
        self.events_file = self.root / "events"
        self.verify_count = self.root / "verify-count"
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
        return path

    def _write_fake_commands(self):
        self._write_executable(
            "docker",
            r"""
            #!/usr/bin/env bash
            set -eu
            printf 'docker %s\n' "$*" >>"$MONITORING_TEST_EVENTS"
            state="$MONITORING_TEST_STATE"
            if [[ "$1" == "inspect" ]]; then
              [[ -e "$state/$2" ]] || exit 1
              if [[ " $* " == *" --format "* ]]; then
                if [[ "${FAIL_REVISION:-0}" == "1" ]]; then
                  printf 'stale-revision\n'
                else
                  printf '%s\n' "$MONITORING_DEPLOY_REVISION"
                fi
              fi
              exit
            fi
            if [[ "$1" == "stop" ]]; then
              [[ "${FAIL_STOP:-0}" != "1" ]]
              exit
            fi
            if [[ "$1" == "start" ]]; then
              [[ "${FAIL_START:-0}" != "1" ]]
              exit
            fi
            if [[ "$1" == "rename" ]]; then
              [[ "${FAIL_RENAME:-0}" != "1" ]]
              mv "$state/$2" "$state/$3"
              exit
            fi
            if [[ "$1" == "rm" ]]; then
              target="${!#}"
              [[ "${FAIL_CLEANUP:-0}" != "1" || "$target" != *-rollback ]]
              rm -f "$state/$target"
              exit
            fi
            if [[ "$1" == "compose" ]]; then
              args=" $* "
              if [[ "$args" == *" config --quiet "* ]]; then
                [[ "${FAIL_CONFIG:-0}" != "1" ]]
                exit
              fi
              if [[ "$args" == *" pull --quiet "* ]]; then
                [[ "${FAIL_PULL:-0}" != "1" ]]
                exit
              fi
              if [[ "$args" == *" up -d --no-deps --force-recreate "* ]]; then
                if [[ "$args" != *" --project-name feelm-monitoring-$CI_PIPELINE_ID "* ]]; then
                  rm -f "$state"/*-rollback
                fi
                for name in feelm-prometheus feelm-grafana feelm-mattermost-alert-relay feelm-node-exporter feelm-postgres-exporter feelm-redis-exporter; do
                  : >"$state/$name"
                done
                [[ "${FAIL_UP:-0}" != "1" ]]
                exit
              fi
            fi
            exit 1
            """,
        )
        self.python = self._write_executable(
            "python3",
            r"""
            #!/usr/bin/env bash
            set -eu
            printf 'verify %s\n' "$*" >>"$MONITORING_TEST_EVENTS"
            count="$(cat "$MONITORING_TEST_VERIFY_COUNT" 2>/dev/null || printf 0)"
            count="$((count + 1))"
            printf '%s' "$count" >"$MONITORING_TEST_VERIFY_COUNT"
            if [[ "${FAIL_VERIFY_ALWAYS:-0}" == "1" ]]; then exit 1; fi
            if [[ "${FAIL_VERIFY_ONCE:-0}" == "1" && "$count" == "1" ]]; then exit 1; fi
            exit 0
            """,
        )

    def run_deploy(self, *, previous=True, stale_backup=False, recover=False, **behavior):
        if previous:
            for name in CONTAINERS:
                (self.state_dir / name).touch()
        if stale_backup:
            (self.state_dir / f"{CONTAINERS[0]}-rollback").touch()
        env = os.environ.copy()
        env.update({
            "PATH": f"{self.bin_dir.as_posix()}:{env['PATH']}",
            "PYTHON_BIN": self.python.as_posix(),
            "MONITORING_TEST_EVENTS": self.events_file.as_posix(),
            "MONITORING_TEST_STATE": self.state_dir.as_posix(),
            "MONITORING_TEST_VERIFY_COUNT": self.verify_count.as_posix(),
            "MONITORING_HEALTH_ATTEMPTS": "1",
            "MONITORING_HEALTH_INTERVAL_SECONDS": "0",
            "CI_PIPELINE_ID": "344",
        })
        env.update({key: str(value) for key, value in behavior.items()})
        result = subprocess.run(
            [self.bash, str(DEPLOY_SCRIPT)] + (["--recover"] if recover else []),
            cwd=SCRIPT_DIR.parent.parent,
            env=env,
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            check=False,
        )
        events = self.events_file.read_text(encoding="utf-8") if self.events_file.exists() else ""
        state = {path.name for path in self.state_dir.iterdir()}
        return result, state, events

    def test_success_replaces_only_monitoring_containers(self):
        result, state, events = self.run_deploy(CI_COMMIT_SHA="1" * 40)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(state, set(CONTAINERS))
        self.assertIn("config --quiet", events)
        self.assertIn("pull --quiet prometheus grafana", events)
        self.assertIn("mattermost-alert-relay", events)
        self.assertIn("up -d --no-deps --force-recreate", events)
        self.assertIn("--project-name feelm-monitoring-344", events)
        self.assertEqual(events.count("--format"), len(CONTAINERS))
        self.assertNotIn("feelm-backend", events)
        self.assertNotIn(" down ", events)

    def test_candidate_project_reuses_service_network_and_data_volumes(self):
        compose = MONITORING_COMPOSE.read_text(encoding="utf-8")

        self.assertIn("name: compose_default", compose)
        self.assertIn("external: true", compose)
        self.assertIn("name: compose_prometheus_data", compose)
        self.assertIn("name: compose_grafana_data", compose)
        self.assertEqual(compose.count("io.feelm.monitoring.revision:"), len(CONTAINERS))

    def test_config_or_pull_failure_keeps_existing_containers(self):
        for behavior in ({"FAIL_CONFIG": 1}, {"FAIL_PULL": 1}):
            with self.subTest(behavior=behavior):
                result, state, events = self.run_deploy(**behavior)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(state, set(CONTAINERS))
                self.assertNotIn("docker stop", events)

    def test_compose_failure_restores_previous_containers(self):
        result, state, events = self.run_deploy(FAIL_UP=1)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(state, set(CONTAINERS))
        self.assertIn("previous monitoring containers were restored", result.stderr)
        self.assertEqual(events.count("verify "), 1)

    def test_health_failure_restores_previous_containers(self):
        result, state, events = self.run_deploy(FAIL_VERIFY_ONCE=1)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(state, set(CONTAINERS))
        self.assertEqual(events.count("verify "), 2)

    def test_revision_mismatch_restores_previous_containers(self):
        result, state, events = self.run_deploy(
            CI_COMMIT_SHA="1" * 40,
            FAIL_REVISION=1,
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(state, set(CONTAINERS))
        self.assertIn("revision mismatch", result.stderr)
        self.assertEqual(events.count("verify "), 2)

    def test_rollback_start_failure_requires_manual_recovery(self):
        result, state, _ = self.run_deploy(FAIL_VERIFY_ALWAYS=1, FAIL_START=1)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(state, set(CONTAINERS))
        self.assertIn("manual recovery is required", result.stderr)

    def test_rename_failure_restarts_originals_without_candidate(self):
        result, state, events = self.run_deploy(FAIL_RENAME=1)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(state, set(CONTAINERS))
        self.assertNotIn(" up -d ", events)

    def test_first_deployment_failure_removes_candidate_without_volumes(self):
        result, state, events = self.run_deploy(previous=False, FAIL_VERIFY_ALWAYS=1)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(state, set())
        self.assertIn("no previous monitoring containers existed", result.stderr)
        self.assertNotIn(" volume ", events)
        self.assertNotIn(" -v", events)

    def test_stale_rollback_container_blocks_before_compose(self):
        result, _, events = self.run_deploy(stale_backup=True)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(events.count("docker inspect"), 1)
        self.assertNotIn("docker compose", events)

    def test_explicit_recovery_restores_backup_without_compose(self):
        result, state, events = self.run_deploy(stale_backup=True, recover=True)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(state, set(CONTAINERS))
        self.assertIn("Previous monitoring containers recovered", result.stdout)
        self.assertNotIn("docker compose", events)

    def test_missing_environment_file_fails_before_docker(self):
        result, _, events = self.run_deploy(COMPOSE_ENV_FILE=self.root / "missing.env")

        self.assertEqual(result.returncode, 2)
        self.assertIn("does not exist", result.stderr)
        self.assertEqual(events, "")


if __name__ == "__main__":
    unittest.main()
