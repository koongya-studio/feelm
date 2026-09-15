import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class KafkaMonitoringConfigTest(unittest.TestCase):
    def test_exporter_is_private_read_only_and_does_not_change_broker(self):
        compose = (ROOT / "infra/compose/distributed.yml").read_text(encoding="utf-8")

        self.assertIn("kafka-exporter:", compose)
        self.assertIn("danielqsj/kafka-exporter:v1.9.0", compose)
        self.assertIn("--kafka.server=127.0.0.1:9092", compose)
        self.assertIn(
            "--web.listen-address=${DISTRIBUTED_NODE_IP:?DISTRIBUTED_NODE_IP is required}:9308",
            compose,
        )
        self.assertIn("--no-concurrent.enable", compose)
        self.assertIn("--topic.workers=10", compose)
        self.assertIn("read_only: true", compose)
        self.assertIn("no-new-privileges:true", compose)
        self.assertIn("cap_drop:\n      - ALL", compose)
        self.assertNotIn("0.0.0.0:9308", compose)

        kafka_service = compose.split("  kafka:\n", 1)[1].split("\n  consumer:\n", 1)[0]
        self.assertNotIn("JMX_PORT", kafka_service)
        self.assertNotIn("javaagent", kafka_service)

    def test_prometheus_scrapes_only_the_distributed_private_endpoint(self):
        monitoring_compose = (ROOT / "infra/compose/monitoring.yml").read_text(
            encoding="utf-8"
        )
        prometheus = (
            ROOT / "infra/monitoring/prometheus/prometheus.yml"
        ).read_text(encoding="utf-8")

        self.assertIn(
            '"feelm-distributed-node:${DISTRIBUTED_NODE_IP:?DISTRIBUTED_NODE_IP is required}"',
            monitoring_compose,
        )
        self.assertIn("job_name: kafka", prometheus)
        self.assertIn('targets: ["feelm-distributed-node:9308"]', prometheus)
        self.assertIn("cluster: feelm", prometheus)
        self.assertNotIn('targets: ["0.0.0.0:9308"]', prometheus)

    def test_dashboard_covers_broker_topics_partitions_and_consumer_lag(self):
        dashboard_path = (
            ROOT / "infra/monitoring/grafana/dashboards/kafka-overview.json"
        )
        dashboard = json.loads(dashboard_path.read_text(encoding="utf-8"))
        panels = {panel["title"]: panel for panel in dashboard["panels"]}

        expected_metrics = {
            "Kafka exporter target": "up{job=\"kafka\"}",
            "Kafka brokers": "kafka_brokers",
            "Topic message rate": "kafka_topic_partition_current_offset",
            "Topic partitions": "kafka_topic_partitions",
            "Under-replicated partitions": "kafka_topic_partition_under_replicated_partition",
            "Consumer group lag": "kafka_consumergroup_lag",
            "Consumer partition lag": "kafka_consumergroup_lag",
            "Consumer group members": "kafka_consumergroup_members",
        }
        self.assertTrue(expected_metrics.keys() <= panels.keys())

        for title, metric in expected_metrics.items():
            expressions = [target["expr"] for target in panels[title]["targets"]]
            self.assertTrue(any(metric in expression for expression in expressions), title)

        group_lag = panels["Consumer group lag"]["targets"][0]["expr"]
        partition_lag = panels["Consumer partition lag"]["targets"][0]["expr"]
        self.assertIn("consumergroup", group_lag)
        self.assertIn("topic", group_lag)
        self.assertIn("partition", partition_lag)

        panel_ids = [panel["id"] for panel in dashboard["panels"]]
        self.assertEqual(len(panel_ids), len(set(panel_ids)))


if __name__ == "__main__":
    unittest.main()
