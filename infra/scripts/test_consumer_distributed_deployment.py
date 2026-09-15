import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DISTRIBUTED_COMPOSE = ROOT / "infra/compose/distributed.yml"
SERVICE_COMPOSE = ROOT / "infra/compose/service.yml"
ENV_EXAMPLE = ROOT / "infra/.env.example"
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
        ):
            self.assertIn(f"{name}: ${{{name}:-}}", compose)

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
        ):
            self.assertIn(f"{name}=", env_example)

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
