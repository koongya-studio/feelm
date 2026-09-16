import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DISTRIBUTED_COMPOSE = ROOT / "infra/compose/distributed.yml"
SERVICE_COMPOSE = ROOT / "infra/compose/service.yml"
ENV_EXAMPLE = ROOT / "infra/.env.example"
INFRA_README = ROOT / "infra/README.md"
RUNTIME_VERIFY = ROOT / "infra/scripts/verify-datastores.sh"


class ConsumerDistributedDeploymentTest(unittest.TestCase):
    def test_consumer_runs_on_distributed_host_with_required_contract(self) -> None:
        compose = DISTRIBUTED_COMPOSE.read_text(encoding="utf-8")

        self.assertIn("  consumer:\n", compose)
        self.assertIn("context: ../../pipeline/consumer", compose)
        self.assertIn("container_name: feelm-foldin-consumer", compose)
        self.assertIn("network_mode: host", compose)
        self.assertIn("stop_grace_period: 30s", compose)
        self.assertIn("restart: unless-stopped", compose)
        self.assertIn('CONSUMER_KAFKA_BOOTSTRAP_SERVERS: "${DISTRIBUTED_NODE_IP:', compose)
        for name in (
            "CONSUMER_POSTGRES_DSN",
            "CONSUMER_REDIS_URL",
            "CONSUMER_MODEL_URI",
            "CONSUMER_MAPPING_URI",
            "CONSUMER_METADATA_URI",
            "CONSUMER_CONTENT_URI",
            "CONSUMER_GROUP_URI",
            "CONSUMER_DISCOVERY_POPULARITY_URI",
        ):
            self.assertIn(f"{name}: ${{{name}:-}}", compose)
        self.assertIn("CONSUMER_HYBRID_ALPHA: ${CONSUMER_HYBRID_ALPHA:-0.5}", compose)
        self.assertNotIn("CONSUMER_MIN_INPUT_COUNT", compose)

    def test_datastores_remain_loopback_by_default(self) -> None:
        compose = SERVICE_COMPOSE.read_text(encoding="utf-8")

        self.assertIn('${POSTGRES_BIND_ADDRESS:-127.0.0.1}:5432:5432', compose)
        self.assertIn('${REDIS_BIND_ADDRESS:-127.0.0.1}:6379:6379', compose)

    def test_environment_example_documents_cross_node_contract(self) -> None:
        env_example = ENV_EXAMPLE.read_text(encoding="utf-8")

        for name in (
            "POSTGRES_BIND_ADDRESS",
            "REDIS_BIND_ADDRESS",
            "CONSUMER_IMAGE",
            "CONSUMER_POSTGRES_DSN",
            "CONSUMER_REDIS_URL",
            "CONSUMER_MODEL_URI",
            "CONSUMER_MAPPING_URI",
            "CONSUMER_METADATA_URI",
            "CONSUMER_CONTENT_URI",
            "CONSUMER_GROUP_URI",
            "CONSUMER_DISCOVERY_POPULARITY_URI",
            "CONSUMER_HYBRID_ALPHA",
        ):
            self.assertIn(f"{name}=", env_example)

        self.assertIn("CONSUMER_HYBRID_ALPHA=0.5", env_example)
        self.assertNotIn("CONSUMER_MIN_INPUT_COUNT", env_example)

    def test_runbook_documents_hybrid_activation_and_verification(self) -> None:
        readme = INFRA_README.read_text(encoding="utf-8")

        self.assertIn("CONSUMER_CONTENT_URI=hdfs://hdfs-namenode:8020/", readme)
        self.assertIn("CONSUMER_GROUP_URI=hdfs://hdfs-namenode:8020/", readme)
        self.assertIn("CONSUMER_DISCOVERY_POPULARITY_URI=hdfs://hdfs-namenode:8020/", readme)
        self.assertIn("CONSUMER_HYBRID_ALPHA=0.5", readme)
        self.assertNotIn("CONSUMER_MIN_INPUT_COUNT", readme)
        self.assertIn("content artifacts loaded version=", readme)
        self.assertIn("content artifacts not configured; publishing ALS-only candidates", readme)
        self.assertIn("group and Discovery popularity artifacts loaded", readme)
        self.assertIn("group artifacts not configured; Discovery uses ranked-candidate fallback", readme)

    def test_runtime_verification_accepts_only_guarded_remote_bindings(self) -> None:
        verification = RUNTIME_VERIFY.read_text(encoding="utf-8")

        self.assertIn('postgres_bind_address="${POSTGRES_BIND_ADDRESS:-127.0.0.1}"', verification)
        self.assertIn('redis_bind_address="${REDIS_BIND_ADDRESS:-127.0.0.1}"', verification)
        self.assertIn("Remote datastore bindings must both equal SERVICE_NODE_IP", verification)
        self.assertIn("must be an RFC1918 private IPv4 address", verification)
        self.assertIn("-S DOCKER-USER", verification)
        self.assertIn("--dports 5432,6379 -j DROP", verification)


if __name__ == "__main__":
    unittest.main()
