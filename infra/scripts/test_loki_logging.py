import json
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ROOT / "infra/compose/monitoring.yml"
LOKI = ROOT / "infra/monitoring/loki/config.yml"
ALLOY = ROOT / "infra/monitoring/alloy/config.alloy"
DATASOURCE = ROOT / "infra/monitoring/grafana/provisioning/datasources/loki.yml"
ALERTING = ROOT / "infra/monitoring/grafana/provisioning/alerting/feelm-alerts.json"


class LokiLoggingProvisioningTest(unittest.TestCase):
    def test_loki_and_alloy_are_private_persistent_monitoring_services(self) -> None:
        compose = COMPOSE.read_text(encoding="utf-8")
        self.assertIn("grafana/loki:3.7.0", compose)
        self.assertIn("grafana/alloy:v1.19.2", compose)
        self.assertIn('"127.0.0.1:${LOKI_PORT:-3100}:3100"', compose)
        self.assertIn('"127.0.0.1:${ALLOY_PORT:-12345}:12345"', compose)
        self.assertIn("name: compose_loki_data", compose)
        self.assertIn("name: compose_alloy_data", compose)
        self.assertNotIn("0.0.0.0:${LOKI_PORT", compose)
        self.assertGreaterEqual(compose.count("read_only: true"), 3)
        self.assertGreaterEqual(compose.count("no-new-privileges:true"), 3)

    def test_loki_uses_tsdb_v13_and_bounded_retention(self) -> None:
        config = LOKI.read_text(encoding="utf-8")
        for value in ("store: tsdb", "schema: v13", "period: 24h"):
            self.assertIn(value, config)
        self.assertIn("retention_enabled: true", config)
        self.assertIn("retention_period: 168h", config)

    def test_alloy_collects_only_service_logs_and_redacts_before_write(self) -> None:
        config = ALLOY.read_text(encoding="utf-8")
        self.assertIn('regex         = "/feelm-(backend|frontend|nginx)"', config)
        self.assertIn('target_label  = "service"', config)
        self.assertIn('target_label = "environment"', config)
        self.assertIn('replacement  = "production"', config)
        self.assertIn("[REDACTED_CREDENTIAL]", config)
        self.assertIn("[REDACTED_EMAIL]", config)
        self.assertIn("[REDACTED_JWT]", config)
        self.assertLess(config.index('loki.process "sanitize"'), config.index('loki.write "local"'))

        expressions = [
            json.loads(value)
            for value in re.findall(r"expression\s*=\s*(\"(?:\\.|[^\"])*\")", config)
        ]
        samples = (
            ("request password=test-secret failed", "test-secret"),
            ("user email=user@example.com failed", "user@example.com"),
            (
                "token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signaturevalue123",
                "eyJhbGciOiJIUzI1NiJ9",
            ),
        )
        for expression, (sample, sensitive) in zip(expressions, samples, strict=True):
            sanitized = re.sub(expression, "[REDACTED]", sample)
            self.assertNotIn(sensitive, sanitized)
            self.assertIn("[REDACTED]", sanitized)

    def test_grafana_datasource_and_log_alerts_are_provisioned(self) -> None:
        datasource = DATASOURCE.read_text(encoding="utf-8")
        self.assertIn("uid: loki", datasource)
        self.assertIn("url: http://loki:3100", datasource)

        payload = json.loads(ALERTING.read_text(encoding="utf-8"))
        rules = {
            rule["uid"]: rule
            for group in payload["groups"]
            for rule in group["rules"]
        }
        for uid in ("feelm_critical_log_detected", "feelm_error_log_spike"):
            rule = rules[uid]
            self.assertEqual(rule["data"][0]["datasourceUid"], "loki")
            self.assertIn("count_over_time", rule["data"][0]["model"]["expr"])
            self.assertIn("explore_url", rule["annotations"])
            self.assertEqual(rule["labels"]["category"], "logs")
            self.assertEqual(rule["notification_settings"]["receiver"], "FEELM Mattermost")


if __name__ == "__main__":
    unittest.main()
