import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("deploy-distributed-runtime.py")
SPEC = importlib.util.spec_from_file_location("distributed_runtime", SCRIPT)
runtime = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runtime)


class FakeRunner:
    def __init__(self, *, fail_candidate=False, fail_rollback=False):
        self.commands = []
        self.fail_candidate = fail_candidate
        self.fail_rollback = fail_rollback

    def run(self, command, *, env=None, capture=False, combined=False):
        self.commands.append((command, env))
        joined = " ".join(command)
        if "rev-parse" in command:
            return "a" * 40
        if command[-2:] == ["config", "--services"]:
            return "\n".join(runtime.CONTAINERS)
        if any("Config.Image" in item for item in command):
            return "feelm-spark:previous"
        if any("State.Status" in item for item in command):
            return "running healthy"
        if command[-2:] == ["-safemode", "get"]:
            return "Safe mode is OFF"
        if command[-1:] == ["-report"]:
            return "Live datanodes (2):"
        if command[:3] == ["docker", "logs", "--tail"]:
            return "Successfully registered with master spark://172.26.0.121:7077"
        if " up -d " in f" {joined} ":
            compose_path = Path(command[command.index("-f") + 1])
            if self.fail_candidate and "candidate" in str(compose_path):
                raise runtime.DeploymentError("candidate failed")
            if self.fail_rollback and "rollback" in str(compose_path):
                raise runtime.DeploymentError("rollback failed")
        return ""


class DistributedRuntimeTest(unittest.TestCase):
    def test_component_allowlist_rejects_kafka_on_service_node(self):
        with self.assertRaisesRegex(runtime.DeploymentError, "Unsupported component"):
            runtime.parse_components("service", "spark,kafka")

    def test_selected_services_never_include_namenode_init(self):
        services = runtime.selected_services("distributed", ("hdfs", "kafka", "spark"))
        self.assertNotIn("hdfs-namenode-init", services)
        self.assertEqual(
            services,
            ("hdfs-namenode", "hdfs-datanode-2", "kafka", "spark-master", "spark-worker-2"),
        )

    def test_compose_update_has_no_deps_and_no_volume_deletion(self):
        command = runtime.compose_command(
            Path("/candidate"), Path("/safe/runtime.env"), "distributed"
        ) + ["up", "-d", "--no-deps", "--force-recreate", "kafka"]
        joined = " ".join(command)
        self.assertIn("up -d --no-deps --force-recreate kafka", joined)
        self.assertNotIn("down", command)
        self.assertNotIn("-v", command)

    def test_service_compose_uses_running_backend_image_when_not_explicit(self):
        environment = {}
        fake = FakeRunner()

        runtime.ensure_service_backend_image("service", environment, fake)

        self.assertEqual(environment["BACKEND_IMAGE"], "feelm-spark:previous")
        self.assertTrue(any(
            command[-1] == "feelm-backend" for command, _ in fake.commands
        ))

    def test_service_compose_preserves_explicit_backend_image(self):
        environment = {"BACKEND_IMAGE": "registry/backend:explicit"}
        fake = FakeRunner()

        runtime.ensure_service_backend_image("service", environment, fake)

        self.assertEqual(environment["BACKEND_IMAGE"], "registry/backend:explicit")
        self.assertEqual(fake.commands, [])

    def test_service_compose_rejects_missing_running_backend_image(self):
        environment = {}
        fake = FakeRunner()
        fake.run = lambda *args, **kwargs: (_ for _ in ()).throw(
            runtime.DeploymentError("missing")
        )

        with self.assertRaisesRegex(runtime.DeploymentError, "BACKEND_IMAGE is missing"):
            runtime.ensure_service_backend_image("service", environment, fake)

    def test_distributed_compose_does_not_resolve_backend_image(self):
        environment = {}
        fake = FakeRunner()

        runtime.ensure_service_backend_image("distributed", environment, fake)

        self.assertEqual(environment, {})
        self.assertEqual(fake.commands, [])

    def test_distributed_hdfs_uses_separate_namenode_and_datanode_batches(self):
        services = ("hdfs-namenode", "hdfs-datanode-2")
        self.assertEqual(
            runtime.deployment_batches("distributed", ("hdfs",), services),
            (("hdfs-namenode",), ("hdfs-datanode-2",)),
        )

    def test_immutable_release_marker_is_accepted_without_git_checkout(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            revision = "d" * 40
            (root / ".feelm-release-revision").write_text(revision + "\n")
            fake = FakeRunner()
            self.assertEqual(runtime.resolve_commit(root, revision, fake), revision)
            self.assertEqual(fake.commands, [])

    def test_hdfs_probe_uses_isolated_path_and_cleans_it(self):
        fake = FakeRunner()
        runtime.verify_hdfs_read_write("distributed", fake)
        command = fake.commands[0][0]
        self.assertEqual(command[:3], ["docker", "exec", "feelm-hdfs-namenode"])
        self.assertIn("/verification/runtime-config/probe-distributed-", command[-1])
        self.assertIn("hdfs dfs -rm -f", command[5])

    def test_kafka_probe_creates_produces_consumes_and_deletes_isolated_topic(self):
        fake = FakeRunner()
        runtime.verify_kafka_produce_consume(fake)
        command = fake.commands[0][0]
        self.assertEqual(command[:3], ["docker", "exec", "feelm-kafka"])
        self.assertIn("feelm.runtime.config.probe.", command[-2])
        self.assertIn("kafka-topics.sh", command[5])
        self.assertIn("kafka-console-producer.sh", command[5])
        self.assertIn("kafka-console-consumer.sh", command[5])
        self.assertIn("--delete", command[5])

    def test_kafka_quorum_retries_until_ready(self):
        fake = FakeRunner()
        original = fake.run
        attempts = iter([runtime.DeploymentError("not ready"), ""])

        def delayed_quorum(command, **kwargs):
            if any(item.endswith("kafka-metadata-quorum.sh") for item in command):
                result = next(attempts)
                if isinstance(result, Exception):
                    raise result
                return result
            return original(command, **kwargs)

        fake.run = delayed_quorum
        with patch.object(runtime.time, "sleep") as sleep:
            runtime.verify_runtime(
                "distributed", ("kafka",), Path("ignored"), ("kafka",), fake, 2
            )
        sleep.assert_called_once_with(5)

    def test_kafka_probe_failure_restores_previous_checkout(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = root / "candidate"
            rollback = root / "rollback"
            candidate.mkdir()
            rollback.mkdir()
            env_file = root / "runtime.env"
            env_file.write_text("DISTRIBUTED_NODE_IP=192.0.2.2\n", encoding="utf-8")
            args = runtime.parser().parse_args([
                "--node", "distributed",
                "--components", "kafka",
                "--source-root", str(candidate),
                "--rollback-root", str(rollback),
                "--env-file", str(env_file),
                "--expected-commit", "a" * 40,
                "--expected-rollback-commit", "b" * 40,
                "--confirm-deploy",
            ])
            fake = FakeRunner()
            with patch.object(runtime, "resolve_commit", side_effect=["a" * 40, "b" * 40]), \
                 patch.object(runtime, "check_compose"), \
                 patch.object(runtime, "verify_runtime"), \
                 patch.object(
                     runtime,
                     "verify_kafka_produce_consume",
                     side_effect=[runtime.DeploymentError("probe failed"), None],
                 ):
                with self.assertRaisesRegex(runtime.DeploymentError, "previous runtime was restored"):
                    runtime.deploy(args, fake)

            up_commands = [item for item in fake.commands if "up" in item[0]]
            self.assertEqual(len(up_commands), 2)
            candidate_compose = up_commands[0][0][up_commands[0][0].index("-f") + 1]
            rollback_compose = up_commands[1][0][up_commands[1][0].index("-f") + 1]
            self.assertIn("candidate", candidate_compose)
            self.assertIn("rollback", rollback_compose)

    def test_spark_active_workload_is_rejected(self):
        with patch.object(runtime, "read_spark_status", return_value={"activeapps": [{}]}):
            with self.assertRaisesRegex(runtime.DeploymentError, "active applications"):
                runtime.ensure_spark_idle("distributed", Path("ignored"))

    def test_service_node_spark_status_rejects_non_ip_host(self):
        with patch.object(runtime, "load_env_value", return_value="host.example.com"), \
             patch.dict(runtime.os.environ, {}, clear=True):
            with self.assertRaisesRegex(runtime.DeploymentError, "must be an IPv4"):
                runtime.spark_status_url("service", Path("ignored"))

    def test_service_node_spark_status_uses_runner_environment(self):
        with patch.object(runtime, "load_env_value", return_value=None), \
             patch.dict(runtime.os.environ, {"DISTRIBUTED_NODE_IP": "192.0.2.9"}, clear=True):
            self.assertEqual(
                runtime.spark_status_url("service", Path("ignored")),
                "http://192.0.2.9:8080/json/",
            )

    def test_service_worker_registration_uses_local_logs(self):
        fake = FakeRunner()
        with patch.object(runtime, "read_spark_status") as status:
            runtime.verify_runtime(
                "service", ("spark",), Path("ignored"),
                ("spark-worker-1",), fake, 1,
            )
        status.assert_not_called()
        self.assertTrue(any(command[:2] == ["docker", "logs"] for command, _ in fake.commands))

    def test_spark_status_requires_two_alive_workers(self):
        fake = FakeRunner()
        with patch.object(runtime, "read_spark_status", return_value={"workers": [{"state": "ALIVE"}]}), \
             patch.object(runtime.time, "sleep"):
            with self.assertRaisesRegex(runtime.DeploymentError, "two ALIVE"):
                runtime.verify_runtime(
                    "distributed", ("spark",), Path("ignored"),
                    ("spark-master", "spark-worker-2"), fake, 1,
                )

    def test_spark_status_rejects_invalid_worker_document(self):
        fake = FakeRunner()
        with patch.object(runtime, "read_spark_status", return_value={"workers": "ALIVE"}), \
             patch.object(runtime.time, "sleep"):
            with self.assertRaisesRegex(runtime.DeploymentError, "invalid worker list"):
                runtime.verify_runtime(
                    "distributed", ("spark",), Path("ignored"),
                    ("spark-master", "spark-worker-2"), fake, 1,
                )

    def test_hdfs_requires_two_live_datanodes(self):
        fake = FakeRunner()
        original = fake.run

        def without_two_nodes(command, **kwargs):
            if command[-1:] == ["-report"]:
                return "Live datanodes (1):"
            return original(command, **kwargs)

        fake.run = without_two_nodes
        with patch.object(runtime.time, "sleep"):
            with self.assertRaisesRegex(runtime.DeploymentError, "two live DataNodes"):
                runtime.verify_runtime(
                    "service", ("hdfs",), Path("ignored"),
                    ("hdfs-datanode-1",), fake, 1,
                )

    def test_hdfs_retries_until_namenode_rpc_is_ready(self):
        fake = FakeRunner()
        original = fake.run
        reports = iter([
            runtime.DeploymentError("Connection refused"),
            "Live datanodes (1):",
            "Live datanodes (2):",
        ])
        report_calls = []

        def delayed_report(command, **kwargs):
            if command[-1:] == ["-report"]:
                report_calls.append(command)
                result = next(reports)
                if isinstance(result, Exception):
                    raise result
                return result
            return original(command, **kwargs)

        fake.run = delayed_report
        with patch.object(runtime.time, "sleep") as sleep:
            runtime.verify_runtime(
                "distributed", ("hdfs",), Path("ignored"),
                ("hdfs-namenode", "hdfs-datanode-2"), fake, 3,
            )

        self.assertEqual(sleep.call_count, 2)
        self.assertEqual(len(report_calls), 3)

    def test_hdfs_retries_until_namenode_safe_mode_is_off(self):
        fake = FakeRunner()
        original = fake.run
        safe_mode_states = iter(["Safe mode is ON", "Safe mode is OFF"])
        safe_mode_calls = []

        def delayed_safe_mode(command, **kwargs):
            if command[-2:] == ["-safemode", "get"]:
                safe_mode_calls.append(command)
                return next(safe_mode_states)
            return original(command, **kwargs)

        fake.run = delayed_safe_mode
        with patch.object(runtime.time, "sleep") as sleep:
            runtime.verify_runtime(
                "distributed", ("hdfs",), Path("ignored"),
                ("hdfs-namenode", "hdfs-datanode-2"), fake, 2,
            )

        sleep.assert_called_once_with(5)
        self.assertEqual(len(safe_mode_calls), 2)

    def test_hdfs_rejects_namenode_that_remains_in_safe_mode(self):
        fake = FakeRunner()
        original = fake.run

        def safe_mode_on(command, **kwargs):
            if command[-2:] == ["-safemode", "get"]:
                return "Safe mode is ON"
            return original(command, **kwargs)

        fake.run = safe_mode_on
        with patch.object(runtime.time, "sleep"):
            with self.assertRaisesRegex(runtime.DeploymentError, "safe mode is not OFF"):
                runtime.verify_runtime(
                    "distributed", ("hdfs",), Path("ignored"),
                    ("hdfs-namenode", "hdfs-datanode-2"), fake, 2,
                )

    def test_plan_mode_does_not_run_commands(self):
        args = runtime.parser().parse_args([
            "--node", "distributed", "--components", "spark,hdfs",
        ])
        fake = FakeRunner()
        self.assertEqual(runtime.deploy(args, fake), 0)
        self.assertEqual(fake.commands, [])

    def test_running_spark_image_must_match_candidate(self):
        fake = FakeRunner()
        with self.assertRaisesRegex(runtime.DeploymentError, "does not match"):
            runtime.verify_spark_image(
                ("spark-master", "spark-worker-2"), "feelm-spark:candidate", fake
            )

    def test_spark_candidate_is_pulled_and_revision_verified_before_compose(self):
        fake = FakeRunner()
        revision = "c" * 40
        original = fake.run

        def image_metadata(command, **kwargs):
            if command[:3] == ["docker", "image", "inspect"]:
                fake.commands.append((command, kwargs.get("env")))
                return revision
            if command[:4] == ["docker", "run", "--rm", "--entrypoint"]:
                fake.commands.append((command, kwargs.get("env")))
                return revision
            return original(command, **kwargs)

        fake.run = image_metadata
        runtime.preflight_spark_image("repo/spark@sha256:" + "d" * 64, revision, fake)

        self.assertEqual(fake.commands[0][0][:2], ["docker", "pull"])
        self.assertTrue(any(command[:3] == ["docker", "image", "inspect"]
                            for command, _ in fake.commands))
        self.assertTrue(any(command[:2] == ["docker", "run"]
                            for command, _ in fake.commands))

    def test_distributed_preflight_checks_idle_before_image_pull(self):
        revision = "c" * 40
        image = "repo/spark@sha256:" + "d" * 64
        args = runtime.parser().parse_args([
            "--node", "distributed", "--components", "spark",
            "--spark-image", image, "--spark-revision", revision,
            "--preflight-only",
        ])
        order = []
        with patch.object(runtime, "ensure_spark_idle",
                          side_effect=lambda *_: order.append("idle")), \
             patch.object(runtime, "preflight_spark_image",
                          side_effect=lambda *_: order.append("image")):
            self.assertEqual(runtime.deploy(args, FakeRunner()), 0)
        self.assertEqual(order, ["idle", "image"])

    def test_running_spark_revision_must_match_candidate(self):
        fake = FakeRunner()
        with self.assertRaisesRegex(runtime.DeploymentError, "revision does not match"):
            runtime.verify_spark_revision(
                ("spark-master", "spark-worker-2"), "c" * 40, fake
            )

    def test_candidate_failure_uses_previous_checkout_and_image(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = root / "candidate"
            rollback = root / "rollback"
            candidate.mkdir()
            rollback.mkdir()
            env_file = root / "runtime.env"
            env_file.write_text("DISTRIBUTED_NODE_IP=192.0.2.2\n", encoding="utf-8")
            args = runtime.parser().parse_args([
                "--node", "distributed",
                "--components", "spark",
                "--source-root", str(candidate),
                "--rollback-root", str(rollback),
                "--env-file", str(env_file),
                "--expected-commit", "a" * 40,
                "--expected-rollback-commit", "b" * 40,
                "--spark-image", "repo/spark@sha256:" + "d" * 64,
                "--spark-revision", "a" * 40,
                "--confirm-deploy",
            ])
            fake = FakeRunner(fail_candidate=True)
            with patch.object(runtime, "resolve_commit", return_value="a" * 40), \
                 patch.object(runtime, "check_compose"), \
                 patch.object(runtime, "ensure_spark_idle"), \
                 patch.object(runtime, "verify_runtime"), \
                 patch.object(runtime, "preflight_spark_image"), \
                 patch.object(runtime, "verify_spark_image"):
                with self.assertRaisesRegex(runtime.DeploymentError, "previous runtime was restored"):
                    runtime.deploy(args, fake)

            up_commands = [item for item in fake.commands if "up" in item[0]]
            self.assertEqual(len(up_commands), 2)
            candidate_compose = up_commands[0][0][up_commands[0][0].index("-f") + 1]
            rollback_compose = up_commands[1][0][up_commands[1][0].index("-f") + 1]
            self.assertIn("candidate", candidate_compose)
            self.assertIn("rollback", rollback_compose)
            self.assertEqual(up_commands[1][1]["SPARK_IMAGE"], "feelm-spark:previous")

    def test_spark_recovery_mode_skips_unavailable_master_idle_check(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = root / "candidate"
            rollback = root / "rollback"
            candidate.mkdir()
            rollback.mkdir()
            env_file = root / "runtime.env"
            env_file.write_text("DISTRIBUTED_NODE_IP=192.0.2.2\n", encoding="utf-8")
            args = runtime.parser().parse_args([
                "--node", "distributed", "--components", "spark",
                "--source-root", str(candidate), "--rollback-root", str(rollback),
                "--env-file", str(env_file), "--expected-commit", "a" * 40,
                "--expected-rollback-commit", "b" * 40,
                "--spark-image", "repo/spark@sha256:" + "d" * 64,
                "--spark-revision", "a" * 40,
                "--rollback-spark-image", "repo/spark@sha256:" + "e" * 64,
                "--rollback-spark-revision", "b" * 40,
                "--spark-recovery-mode", "--confirm-deploy",
            ])
            fake = FakeRunner()
            original = fake.run

            def mixed_running_images(command, **kwargs):
                if any("Config.Image" in item for item in command):
                    fake.commands.append((command, kwargs.get("env")))
                    return (
                        "repo/spark@sha256:" + "d" * 64
                        if command[-1] == "feelm-spark-master"
                        else "repo/spark@sha256:" + "e" * 64
                    )
                return original(command, **kwargs)

            fake.run = mixed_running_images
            with patch.object(runtime, "resolve_commit", side_effect=["a" * 40, "b" * 40]), \
                 patch.object(runtime, "check_compose"), \
                 patch.object(runtime, "ensure_spark_idle") as idle, \
                 patch.object(runtime, "preflight_spark_image"), \
                 patch.object(runtime, "verify_runtime"), \
                 patch.object(runtime, "verify_spark_image"), \
                 patch.object(runtime, "verify_spark_revision"):
                self.assertEqual(runtime.deploy(args, fake), 0)
            idle.assert_not_called()

    def test_service_spark_deployment_rechecks_master_idle_before_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = root / "candidate"
            rollback = root / "rollback"
            candidate.mkdir()
            rollback.mkdir()
            env_file = root / "runtime.env"
            env_file.write_text("DISTRIBUTED_NODE_IP=192.0.2.2\n", encoding="utf-8")
            args = runtime.parser().parse_args([
                "--node", "service", "--components", "spark",
                "--source-root", str(candidate), "--rollback-root", str(rollback),
                "--env-file", str(env_file), "--expected-commit", "a" * 40,
                "--expected-rollback-commit", "b" * 40,
                "--spark-image", "repo/spark@sha256:" + "d" * 64,
                "--spark-revision", "a" * 40,
                "--rollback-spark-revision", "b" * 40,
                "--confirm-deploy",
            ])
            with patch.object(runtime, "resolve_commit", side_effect=["a" * 40, "b" * 40]), \
                 patch.object(runtime, "check_compose"), \
                 patch.object(runtime, "ensure_spark_idle") as idle, \
                 patch.object(runtime, "preflight_spark_image"), \
                 patch.object(runtime, "verify_runtime"), \
                 patch.object(runtime, "verify_spark_image"), \
                 patch.object(runtime, "verify_spark_revision"):
                self.assertEqual(runtime.deploy(args, FakeRunner()), 0)
            idle.assert_called_once_with("service", env_file.resolve())

    def test_rollback_failure_requires_manual_recovery(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = root / "candidate"
            rollback = root / "rollback"
            candidate.mkdir()
            rollback.mkdir()
            env_file = root / "runtime.env"
            env_file.write_text("DISTRIBUTED_NODE_IP=192.0.2.2\n", encoding="utf-8")
            args = runtime.parser().parse_args([
                "--node", "distributed",
                "--components", "hdfs",
                "--source-root", str(candidate),
                "--rollback-root", str(rollback),
                "--env-file", str(env_file),
                "--expected-commit", "a" * 40,
                "--expected-rollback-commit", "b" * 40,
                "--confirm-deploy",
            ])
            fake = FakeRunner(fail_candidate=True, fail_rollback=True)
            with patch.object(runtime, "resolve_commit", side_effect=["a" * 40, "b" * 40]), \
                 patch.object(runtime, "check_compose"), \
                 patch.object(runtime, "verify_runtime"):
                with self.assertRaisesRegex(runtime.DeploymentError, "manual recovery"):
                    runtime.deploy(args, fake)


if __name__ == "__main__":
    unittest.main()
