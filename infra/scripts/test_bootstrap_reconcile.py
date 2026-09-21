import importlib.util
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("bootstrap-reconcile.py")
SPEC = importlib.util.spec_from_file_location("bootstrap_reconcile", SCRIPT)
bootstrap = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = bootstrap
SPEC.loader.exec_module(bootstrap)


class FakeRunner:
    def __init__(self, volumes=None, states=None):
        self.volumes = volumes or {}
        self.states = states or {}
        self.commands = []

    def run(self, command):
        self.commands.append(command)
        if command[:3] == ["docker", "volume", "ls"]:
            logical = command[command.index("--filter") + 1].rsplit("=", 1)[1]
            value = self.volumes.get(logical)
            if isinstance(value, list):
                return "\n".join(value)
            return value or ""
        if command[:3] == ["docker", "image", "inspect"]:
            return "sha256:image"
        if command[:2] == ["docker", "run"]:
            volume = next(item for item in command if item.startswith("type=volume"))
            name = volume.split("src=", 1)[1].split(",", 1)[0]
            return self.states[name]
        return "ok"


def env_file(directory, *, cluster_id="approved-cluster"):
    path = Path(directory) / "runtime.env"
    path.write_text(
        "SERVICE_NODE_IP=10.0.0.10\n"
        "DISTRIBUTED_NODE_IP=10.0.0.11\n"
        f"KAFKA_CLUSTER_ID={cluster_id}\n",
        encoding="utf-8",
    )
    return path


class BootstrapReconcileTest(unittest.TestCase):
    def test_pipeline_order_is_fixed(self):
        self.assertEqual(
            bootstrap.PIPELINE_STEPS,
            (
                "preflight", "postgresql", "redis", "hdfs", "kafka",
                "kafka-topics", "spark", "backend", "consumer",
                "frontend-nginx", "monitoring-auxiliary", "verify-all",
            ),
        )

    def test_bootstrap_allows_only_empty_distributed_state_to_initialize(self):
        runner = FakeRunner(
            volumes={
                "hdfs_namenode": "compose_hdfs_namenode",
                "kafka_data": "compose_kafka_data",
            },
            states={
                "compose_hdfs_namenode": "empty",
                "compose_kafka_data": "empty",
            },
        )
        with tempfile.TemporaryDirectory() as directory:
            report = bootstrap.preflight(
                "bootstrap", "distributed", env_file(directory), runner
            )
        actions = {item.component: item.action for item in report.decisions}
        self.assertEqual(actions["hdfs"], "initialize-once")
        self.assertEqual(actions["kafka"], "initialize-once")

    def test_existing_hdfs_is_never_formatted(self):
        runner = FakeRunner(
            volumes={
                "hdfs_namenode": "compose_hdfs_namenode",
                "kafka_data": "compose_kafka_data",
            },
            states={
                "compose_hdfs_namenode": "formatted",
                "compose_kafka_data": "cluster:approved-cluster",
            },
        )
        with tempfile.TemporaryDirectory() as directory:
            report = bootstrap.preflight(
                "bootstrap", "distributed", env_file(directory), runner
            )
        self.assertEqual(report.decisions[0].action, "reuse-without-format")

    def test_nonempty_unformatted_hdfs_is_refused(self):
        runner = FakeRunner(
            volumes={"hdfs_namenode": "compose_hdfs_namenode"},
            states={"compose_hdfs_namenode": "nonempty"},
        )
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(bootstrap.PreflightError, "formatting is refused"):
                bootstrap.preflight(
                    "bootstrap", "distributed", env_file(directory), runner
                )

    def test_existing_kafka_cluster_id_must_match(self):
        runner = FakeRunner(
            volumes={
                "hdfs_namenode": "compose_hdfs_namenode",
                "kafka_data": "compose_kafka_data",
            },
            states={
                "compose_hdfs_namenode": "formatted",
                "compose_kafka_data": "cluster:existing-cluster",
            },
        )
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(bootstrap.PreflightError, "regeneration is refused"):
                bootstrap.preflight(
                    "reconcile", "distributed", env_file(directory), runner
                )

    def test_reconcile_refuses_missing_service_volume(self):
        runner = FakeRunner(volumes={"postgres_data": "compose_postgres_data"})
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(bootstrap.PreflightError, "redis"):
                bootstrap.preflight("reconcile", "service", env_file(directory), runner)

    def test_volume_probe_is_read_only_and_network_is_disabled(self):
        runner = FakeRunner(
            volumes={"hdfs_namenode": "compose_hdfs_namenode"},
            states={"compose_hdfs_namenode": "formatted"},
        )
        bootstrap.hdfs_decision("bootstrap", runner)
        probe = next(command for command in runner.commands if command[:2] == ["docker", "run"])
        self.assertIn("--read-only", probe)
        self.assertIn("none", probe)
        self.assertTrue(any("readonly" in item for item in probe))

    def test_ambiguous_volume_is_refused(self):
        runner = FakeRunner(volumes={"kafka_data": ["one", "two"]})
        with self.assertRaisesRegex(bootstrap.PreflightError, "Ambiguous"):
            bootstrap.find_volume("kafka_data", runner)

    def test_topic_script_is_idempotent_and_has_no_delete_operation(self):
        source = Path(__file__).with_name("ensure-kafka-topics.sh").read_text(encoding="utf-8")
        self.assertIn("--if-not-exists", source)
        for topic in (
            "feelm.rating.v1", "feelm.dismiss.v1", "feelm.view.v1",
            "feelm.recommendation-click.v1", "feelm.ott-click.v1",
            "feelm.candidate-updated.v1", "feelm.party-refresh.v1",
            "feelm.party-candidate-updated.v1",
        ):
            self.assertIn(topic, source)
        self.assertNotIn("--delete", source)
        self.assertNotIn("down -v", source)

    def test_process_environment_overrides_optional_env_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = env_file(directory, cluster_id="file-cluster")
            with patch.dict(os.environ, {"KAFKA_CLUSTER_ID": "protected-cluster"}, clear=False):
                loaded = bootstrap.load_env(path)
        self.assertEqual(loaded["KAFKA_CLUSTER_ID"], "protected-cluster")

    def test_mutating_driver_has_explicit_bootstrap_confirmation_and_no_volume_delete(self):
        source = Path(__file__).with_name("bootstrap-reconcile-step.sh").read_text(
            encoding="utf-8"
        )
        self.assertIn("INFRA_BOOTSTRAP_CONFIRM", source)
        self.assertIn("INITIALIZE_EMPTY_STATE", source)
        self.assertIn("initialize-once", source)
        self.assertIn("deploy-postgresql.sh", source)
        self.assertIn("deploy-distributed-observability.sh", source)
        self.assertIn("deploy-spark-history.sh", source)
        for forbidden in ("down -v", "volume rm", "docker volume prune"):
            self.assertNotIn(forbidden, source)

    def test_all_release_images_must_be_immutable_digests(self):
        source = Path(__file__).with_name("bootstrap-reconcile-step.sh").read_text(
            encoding="utf-8"
        )
        for variable in (
            "INFRA_BACKEND_IMAGE", "INFRA_FRONTEND_IMAGE",
            "INFRA_CONSUMER_IMAGE", "INFRA_SPARK_IMAGE",
        ):
            self.assertIn(f"require_digest {variable}", source)
        self.assertIn("require_revision INFRA_SPARK_REVISION", source)
        self.assertIn("deploy-spark-code.sh\" initialize", source)
        self.assertIn("deploy-spark-code.sh\" preflight", source)


if __name__ == "__main__":
    unittest.main()
