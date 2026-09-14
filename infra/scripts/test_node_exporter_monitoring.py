import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class NodeExporterMonitoringConfigTest(unittest.TestCase):
    def test_distributed_exporter_is_private_read_only_and_restartable(self):
        compose = (ROOT / "infra/compose/distributed.yml").read_text(encoding="utf-8")

        self.assertIn("feelm-node-exporter-distributed", compose)
        self.assertIn("restart: unless-stopped", compose)
        self.assertIn("pid: host", compose)
        self.assertIn("read_only: true", compose)
        self.assertIn(
            '"${DISTRIBUTED_NODE_IP:?DISTRIBUTED_NODE_IP is required}:9100:9100"',
            compose,
        )
        self.assertIn("/:/host:ro,rslave", compose)
        self.assertIn("no-new-privileges:true", compose)
        self.assertIn("cap_drop:\n      - ALL", compose)
        self.assertNotIn('"0.0.0.0:9100:9100"', compose)

    def test_prometheus_resolves_only_the_configured_distributed_private_ip(self):
        compose = (ROOT / "infra/compose/monitoring.yml").read_text(encoding="utf-8")
        prometheus = (
            ROOT / "infra/monitoring/prometheus/prometheus.yml"
        ).read_text(encoding="utf-8")

        self.assertIn(
            '"feelm-distributed-node:${DISTRIBUTED_NODE_IP:?DISTRIBUTED_NODE_IP is required}"',
            compose,
        )
        self.assertIn('targets: ["node-exporter:9100"]', prometheus)
        self.assertIn('targets: ["feelm-distributed-node:9100"]', prometheus)
        self.assertIn("node_name: J15E106", prometheus)
        self.assertIn("node_role: service", prometheus)
        self.assertIn("node_name: J15E106A", prometheus)
        self.assertIn("node_role: distributed", prometheus)

    def test_dashboard_separates_both_nodes_and_covers_host_resources(self):
        dashboard_path = (
            ROOT
            / "infra/monitoring/grafana/dashboards/service-overview.json"
        )
        dashboard = json.loads(dashboard_path.read_text(encoding="utf-8"))
        panels = {panel["title"]: panel for panel in dashboard["panels"]}

        expected_titles = {
            "Host CPU usage",
            "Host memory usage",
            "Host disk throughput",
            "Host filesystem available",
            "Host network throughput",
        }
        self.assertTrue(expected_titles.issubset(panels))

        for title in expected_titles:
            targets = panels[title]["targets"]
            self.assertTrue(all("node_name" in target["expr"] for target in targets))
            self.assertTrue(
                all('node_name=~".+"' in target["expr"] for target in targets)
            )
            self.assertTrue(all("{{node_name}}" in target["legendFormat"] for target in targets))

        panel_ids = [panel["id"] for panel in dashboard["panels"]]
        self.assertEqual(len(panel_ids), len(set(panel_ids)))


if __name__ == "__main__":
    unittest.main()
