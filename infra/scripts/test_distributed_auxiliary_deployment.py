import os
import shutil
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
COMPOSE = (SCRIPT_DIR.parent / "compose" / "distributed.yml").read_text(encoding="utf-8")
OBSERVABILITY = (SCRIPT_DIR / "deploy-distributed-observability.sh").read_text(
    encoding="utf-8"
)
HISTORY = (SCRIPT_DIR / "deploy-spark-history.sh").read_text(encoding="utf-8")


class DistributedAuxiliaryDeploymentContractTest(unittest.TestCase):
    def test_observability_deploy_selects_only_auxiliary_services(self):
        self.assertIn("services=(kafka-exporter kafka-ui node-exporter)", OBSERVABILITY)
        self.assertIn('up -d --no-deps --force-recreate "${services[@]}"', OBSERVABILITY)
        self.assertIn('pull --quiet "${services[@]}"', OBSERVABILITY)
        for forbidden in ("spark-history-server", " hdfs-namenode", " kafka)", " down ", "down -v"):
            self.assertNotIn(forbidden, OBSERVABILITY)

    def test_observability_preserves_and_can_recover_previous_containers(self):
        for expected in (
            "feelm-kafka-exporter",
            "feelm-kafka-ui",
            "feelm-node-exporter-distributed",
            'docker rename "${container}" "${container}-rollback"',
            "deploy-distributed-observability.sh --recover",
            "previous distributed observability",
            "containers were restored",
        ):
            self.assertIn(expected, OBSERVABILITY)

    def test_compose_labels_each_long_running_auxiliary_with_deployment_revision(self):
        self.assertEqual(
            COMPOSE.count("io.feelm.distributed-observability.revision:"),
            3,
        )
        self.assertEqual(COMPOSE.count("io.feelm.spark-history.revision:"), 1)
        self.assertIn("OBSERVABILITY_DEPLOY_REVISION", OBSERVABILITY)
        self.assertIn("SPARK_HISTORY_DEPLOY_REVISION", HISTORY)
        self.assertIn("verify_candidate_revision", OBSERVABILITY)
        self.assertIn("verify_candidate_revision", HISTORY)

    def test_history_initializes_hdfs_before_replacing_server(self):
        init = HISTORY.index("--profile init run --rm --no-deps spark-history-init")
        stop = HISTORY.index('docker stop "${container}"')
        deploy = HISTORY.index("up -d --no-deps --force-recreate spark-history-server")
        self.assertLess(init, stop)
        self.assertLess(stop, deploy)

    def test_history_reuses_running_spark_image_and_supports_recovery(self):
        for expected in (
            "docker inspect feelm-spark-master",
            "export SPARK_IMAGE",
            "deploy-spark-history.sh --recover",
            'docker rename "${container}" "${container}-rollback"',
            "previous Spark History container was restored",
        ):
            self.assertIn(expected, HISTORY)
        for forbidden in (" down ", "down -v", "docker volume rm"):
            self.assertNotIn(forbidden, HISTORY)

    def test_both_deployments_validate_compose_before_mutation(self):
        self.assertLess(OBSERVABILITY.index("config --quiet"), OBSERVABILITY.index("docker stop"))
        self.assertLess(HISTORY.index("config --quiet"), HISTORY.index("docker stop"))


class DistributedAuxiliaryDeploymentFlowTest(unittest.TestCase):
    OBSERVABILITY_CONTAINERS = (
        "feelm-kafka-exporter",
        "feelm-kafka-ui",
        "feelm-node-exporter-distributed",
    )

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
        self.events = self.root / "events"
        self.verify_count = self.root / "verify-count"
        self._write_fakes()

    def tearDown(self):
        self.temp_dir.cleanup()

    @staticmethod
    def _find_bash():
        if os.name != "nt":
            return shutil.which("bash")
        git_bash = Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "Git/bin/bash.exe"
        return str(git_bash) if git_bash.is_file() else None

    def _write_executable(self, name, content):
        path = self.bin_dir / name
        path.write_text(textwrap.dedent(content).lstrip(), encoding="utf-8")
        path.chmod(0o755)
        return path

    def _write_fakes(self):
        self._write_executable(
            "docker",
            r"""
            #!/usr/bin/env bash
            set -eu
            printf 'docker %s\n' "$*" >>"$AUX_TEST_EVENTS"
            state="$AUX_TEST_STATE"
            if [[ "$1" == "pull" ]]; then exit 0; fi
            if [[ "$1" == "image" ]]; then
              if [[ "${FAIL_RUNTIME_LABEL:-0}" == "1" ]]; then printf '<no value>\n'; exit 0; fi
              printf '%s%s\n' 'registry.invalid/runtime@sha256:' \
                'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
              exit 0
            fi
            if [[ "$1" == "inspect" ]]; then
              if [[ " $* " == *" feelm-spark-master --format "* ]]; then
                printf '%s%s\n' 'registry.invalid/spark@sha256:' \
                  'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
                exit 0
              fi
              if [[ " $* " == *" --format "* ]]; then
                if [[ "$*" == *'.Config.Image'* ]]; then
                  printf '%s\n' "${SPARK_IMAGE}"
                elif [[ "${FAIL_REVISION:-0}" == "1" ]]; then
                  printf '%s\n' 'stale-revision'
                elif [[ "$2" == "feelm-spark-history-server" ]]; then
                  printf '%s\n' "$SPARK_HISTORY_DEPLOY_REVISION"
                else
                  printf '%s\n' "$OBSERVABILITY_DEPLOY_REVISION"
                fi
                exit 0
              fi
              [[ -e "$state/$2" ]]
              exit
            fi
            if [[ "$1" == "stop" ]]; then exit 0; fi
            if [[ "$1" == "start" ]]; then
              [[ "${FAIL_START:-0}" != "1" ]]
              exit
            fi
            if [[ "$1" == "rename" ]]; then
              mv "$state/$2" "$state/$3"
              exit
            fi
            if [[ "$1" == "rm" ]]; then
              rm -f "$state/${!#}"
              exit
            fi
            if [[ "$1" == "compose" ]]; then
              args=" $* "
              if [[ "$args" == *" config --quiet "* || "$args" == *" pull --quiet "* ]]; then
                exit 0
              fi
              if [[ "$args" == *" --profile init run --rm --no-deps spark-history-init "* ]]; then
                [[ "${FAIL_INIT:-0}" != "1" ]]
                exit
              fi
              if [[ "$args" == *" up -d --no-deps --force-recreate "* ]] \
                && [[ "$args" == *" kafka-exporter kafka-ui node-exporter "* ]]; then
                for name in feelm-kafka-exporter feelm-kafka-ui feelm-node-exporter-distributed; do
                  : >"$state/$name"
                done
                [[ "${FAIL_UP:-0}" != "1" ]]
                exit
              fi
              if [[ "$args" == *" up -d --no-deps --force-recreate spark-history-server "* ]]; then
                : >"$state/feelm-spark-history-server"
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
            printf 'verify %s\n' "$*" >>"$AUX_TEST_EVENTS"
            count="$(cat "$AUX_TEST_VERIFY_COUNT" 2>/dev/null || printf 0)"
            count="$((count + 1))"
            printf '%s' "$count" >"$AUX_TEST_VERIFY_COUNT"
            if [[ "${FAIL_VERIFY_ALWAYS:-0}" == "1" ]]; then exit 1; fi
            if [[ "${FAIL_VERIFY_ONCE:-0}" == "1" && "$count" == "1" ]]; then exit 1; fi
            exit 0
            """,
        )

    def run_script(self, name, previous, **behavior):
        for container in previous:
            (self.state_dir / container).touch()
        env = os.environ.copy()
        env.update(
            {
                "PATH": f"{self.bin_dir.as_posix()}:{env['PATH']}",
                "PYTHON_BIN": self.python.as_posix(),
                "AUX_TEST_EVENTS": self.events.as_posix(),
                "AUX_TEST_STATE": self.state_dir.as_posix(),
                "AUX_TEST_VERIFY_COUNT": self.verify_count.as_posix(),
                "DISTRIBUTED_NODE_IP": "192.0.2.10",
                "DISTRIBUTED_OBSERVABILITY_HEALTH_ATTEMPTS": "1",
                "DISTRIBUTED_OBSERVABILITY_HEALTH_INTERVAL_SECONDS": "0",
                "SPARK_HISTORY_HEALTH_ATTEMPTS": "1",
                "SPARK_HISTORY_HEALTH_INTERVAL_SECONDS": "0",
                "CI_PIPELINE_ID": "653",
                "CI_COMMIT_SHA": "6536536536536536536536536536536536536536",
            }
        )
        env.update({key: str(value) for key, value in behavior.items()})
        result = subprocess.run(
            [self.bash, str(SCRIPT_DIR / name)],
            cwd=SCRIPT_DIR.parent.parent,
            env=env,
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            check=False,
        )
        events = self.events.read_text(encoding="utf-8") if self.events.exists() else ""
        state = {path.name for path in self.state_dir.iterdir()}
        return result, state, events

    def test_history_rejects_missing_runtime_label_before_stopping_previous(self):
        result, state, events = self.run_script(
            "deploy-spark-history.sh", ("feelm-spark-history-server",), FAIL_RUNTIME_LABEL=1
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(state, {"feelm-spark-history-server"})
        self.assertNotIn("docker stop", events)

    def test_history_pulls_runtime_digest_and_verifies_candidate_image(self):
        runtime = "registry.invalid/runtime@sha256:" + "b" * 64
        result, state, events = self.run_script(
            "deploy-spark-history.sh", ("feelm-spark-history-server",),
            SPARK_RUNTIME_IMAGE=runtime
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(state, {"feelm-spark-history-server"})
        self.assertLess(events.index("docker pull " + runtime), events.index("docker stop"))
        self.assertIn("docker inspect feelm-spark-history-server --format {{.Config.Image}}", events)

    def test_history_rejects_pipeline_runtime_mismatch_before_stopping_previous(self):
        result, state, events = self.run_script(
            "deploy-spark-history.sh", ("feelm-spark-history-server",),
            SPARK_RUNTIME_IMAGE="registry.invalid/runtime@sha256:" + "c" * 64
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(state, {"feelm-spark-history-server"})
        self.assertNotIn("docker stop", events)

    def test_observability_success_replaces_only_three_auxiliary_containers(self):
        result, state, events = self.run_script(
            "deploy-distributed-observability.sh", self.OBSERVABILITY_CONTAINERS
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(state, set(self.OBSERVABILITY_CONTAINERS))
        self.assertIn(
            "up -d --no-deps --force-recreate kafka-exporter kafka-ui node-exporter",
            events,
        )
        self.assertNotIn("feelm-kafka ", events)
        self.assertNotIn("hdfs-namenode", events)

    def test_observability_health_failure_restores_previous_containers(self):
        result, state, events = self.run_script(
            "deploy-distributed-observability.sh",
            self.OBSERVABILITY_CONTAINERS,
            FAIL_VERIFY_ONCE=1,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(state, set(self.OBSERVABILITY_CONTAINERS))
        self.assertEqual(events.count("verify "), 2)
        self.assertIn("were restored", result.stderr)

    def test_history_init_failure_keeps_previous_server_running(self):
        result, state, events = self.run_script(
            "deploy-spark-history.sh", ("feelm-spark-history-server",), FAIL_INIT=1
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(state, {"feelm-spark-history-server"})
        self.assertNotIn("docker stop feelm-spark-history-server", events)

    def test_history_health_failure_restores_previous_server(self):
        result, state, events = self.run_script(
            "deploy-spark-history.sh",
            ("feelm-spark-history-server",),
            FAIL_VERIFY_ONCE=1,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(state, {"feelm-spark-history-server"})
        self.assertEqual(events.count("verify "), 2)
        self.assertIn("was restored", result.stderr)

    def test_revision_mismatch_restores_each_previous_deployment(self):
        for script, previous in (
            ("deploy-distributed-observability.sh", self.OBSERVABILITY_CONTAINERS),
            ("deploy-spark-history.sh", ("feelm-spark-history-server",)),
        ):
            with self.subTest(script=script):
                result, state, _ = self.run_script(script, previous, FAIL_REVISION=1)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(state, set(previous))
                for path in self.state_dir.iterdir():
                    path.unlink()
                self.events.unlink(missing_ok=True)
                self.verify_count.unlink(missing_ok=True)


if __name__ == "__main__":
    unittest.main()
