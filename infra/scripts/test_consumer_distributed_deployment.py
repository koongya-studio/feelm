import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DISTRIBUTED_COMPOSE = ROOT / "infra/compose/distributed.yml"
SERVICE_COMPOSE = ROOT / "infra/compose/service.yml"
ENV_EXAMPLE = ROOT / "infra/.env.example"
INFRA_README = ROOT / "infra/README.md"
RUNTIME_VERIFY = ROOT / "infra/scripts/verify-datastores.sh"


class ConsumerDistributedDeploymentTest(unittest.TestCase):
    def test_gbt_mode_and_pins_are_shared_without_party_database_access(self) -> None:
        compose = DISTRIBUTED_COMPOSE.read_text(encoding="utf-8")
        personal = compose.split("  consumer:\n", 1)[1].split("  party-consumer:\n", 1)[0]
        party = compose.split("  party-consumer:\n", 1)[1].split("  node-exporter:\n", 1)[0]
        for name in ("CONSUMER_RUNTIME", "CONSUMER_GBT_BUNDLE_URI",
                     "CONSUMER_GBT_SERVING_REVISION", "CONSUMER_GBT_MAX_SNAPSHOT_BYTES"):
            fallback = "legacy" if name == "CONSUMER_RUNTIME" else ""
            line = f"{name}: ${{{name}:-{fallback}}}"
            self.assertIn(line, personal)
            self.assertIn(line, party)
            self.assertIn(f"{name}=", ENV_EXAMPLE.read_text(encoding="utf-8"))
        self.assertIn("CONSUMER_GBT_SNAPSHOT_RETENTION_SECONDS:", personal)
        self.assertNotIn("CONSUMER_GBT_SNAPSHOT_RETENTION_SECONDS:", party)
        self.assertNotIn("POSTGRES", party)

    def test_consumer_runs_on_distributed_host_with_required_contract(self) -> None:
        compose = DISTRIBUTED_COMPOSE.read_text(encoding="utf-8")

        self.assertIn("  consumer:\n", compose)
        self.assertIn("context: ../../pipeline/consumer", compose)
        self.assertIn("container_name: feelm-foldin-consumer", compose)
        self.assertIn("network_mode: host", compose)
        self.assertIn("stop_grace_period: 30s", compose)
        self.assertIn("restart: unless-stopped", compose)
        self.assertIn('CONSUMER_KAFKA_BOOTSTRAP_SERVERS: "${DISTRIBUTED_NODE_IP:', compose)
        self.assertIn(
            "CONSUMER_DISMISS_KAFKA_TOPIC: ${CONSUMER_DISMISS_KAFKA_TOPIC:-feelm.dismiss.v1}",
            compose,
        )
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
        self.assertIn("CONSUMER_DISMISS_SCORE: ${CONSUMER_DISMISS_SCORE:-1.0}", compose)
        self.assertIn(
            "CONSUMER_DISMISS_INPUT_VERSION: ${CONSUMER_DISMISS_INPUT_VERSION:-dismiss-virtual-rating-v1}",
            compose,
        )
        self.assertNotIn("CONSUMER_MIN_INPUT_COUNT", compose)

    def test_datastores_remain_loopback_by_default(self) -> None:
        compose = SERVICE_COMPOSE.read_text(encoding="utf-8")

        self.assertIn('${POSTGRES_BIND_ADDRESS:-127.0.0.1}:5432:5432', compose)
        self.assertIn('${REDIS_BIND_ADDRESS:-127.0.0.1}:6379:6379', compose)

    def test_party_consumer_is_an_independent_process_and_group(self) -> None:
        compose = DISTRIBUTED_COMPOSE.read_text(encoding="utf-8")

        self.assertIn("  party-consumer:\n", compose)
        self.assertIn("container_name: feelm-party-consumer", compose)
        self.assertIn(
            'command: ["/app/.venv/bin/python", "-m", "feelm_consumer.party_main"]',
            compose,
        )
        self.assertIn("- party-consumer", compose)
        self.assertIn(
            "PARTY_CONSUMER_KAFKA_GROUP_ID: ${PARTY_CONSUMER_KAFKA_GROUP_ID:-feelm-party-consumer-v1}",
            compose,
        )
        self.assertEqual(compose.count("container_name: feelm-foldin-consumer"), 1)

    def test_party_completion_topic_and_backend_group_are_deployment_prerequisites(self) -> None:
        readme = INFRA_README.read_text(encoding="utf-8")

        self.assertIn("--topic feelm.party-candidate-updated.v1", readme)
        self.assertIn("party_candidate_publications", readme)
        self.assertIn("--group feelm-party-candidate-updated-v1", readme)
        self.assertIn("운영 중 입력 토픽의 partition 수를", readme)
        self.assertIn("Python raw Candidate 게시 완료만 나타내는 파생 상태", readme)
        self.assertLess(
            readme.index("--topic feelm.party-candidate-updated.v1"),
            readme.index("--profile party-consumer up"),
        )

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
            "CONSUMER_DISMISS_KAFKA_TOPIC",
            "CONSUMER_DISMISS_SCORE",
            "CONSUMER_DISMISS_INPUT_VERSION",
            "PARTY_CONSUMER_KAFKA_TOPIC",
            "PARTY_CONSUMER_KAFKA_GROUP_ID",
            "PARTY_CONSUMER_REDIS_URL",
            "PARTY_CONSUMER_MODEL_URI",
            "PARTY_CONSUMER_MAPPING_URI",
            "PARTY_CONSUMER_METADATA_URI",
        ):
            self.assertIn(f"{name}=", env_example)

        self.assertIn("CONSUMER_HYBRID_ALPHA=0.5", env_example)
        self.assertIn("CONSUMER_DISMISS_SCORE=1.0", env_example)
        self.assertIn("CONSUMER_DISMISS_INPUT_VERSION=dismiss-virtual-rating-v1", env_example)
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
        self.assertIn("group artifacts not configured; Discovery remains empty", readme)

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
