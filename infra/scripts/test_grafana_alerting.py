import json
from pathlib import Path
import unittest
from urllib.parse import parse_qs, urlparse


ROOT = Path(__file__).resolve().parents[2]
ALERTING = (
    ROOT
    / "infra/monitoring/grafana/provisioning/alerting/feelm-alerts.json"
)


class GrafanaAlertingProvisioningTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.payload = json.loads(ALERTING.read_text(encoding="utf-8"))
        cls.rules = [
            rule
            for group in cls.payload["groups"]
            for rule in group["rules"]
        ]

    def test_required_alert_rules_are_provisioned(self) -> None:
        expected = {
            "feelm_backend_target_down",
            "feelm_exporter_target_down",
            "feelm_backend_5xx_rate",
            "feelm_backend_p95_latency",
            "feelm_host_cpu_high",
            "feelm_host_memory_high",
            "feelm_host_filesystem_low",
            "feelm_critical_log_detected",
            "feelm_error_log_spike",
        }
        self.assertEqual({rule["uid"] for rule in self.rules}, expected)

        expressions = "\n".join(
            query["model"].get("expr", "")
            for rule in self.rules
            for query in rule["data"]
        )
        for metric in (
            'up{job="backend"}',
            'up{job=~"node|kafka|postgres|redis"}',
            "http_server_requests_seconds_count",
            "http_server_requests_seconds_bucket",
            "node_cpu_seconds_total",
            "node_memory_MemAvailable_bytes",
            "node_filesystem_avail_bytes",
            "count_over_time",
            'environment=\"production\"',
        ):
            self.assertIn(metric, expressions)

    def test_rules_delay_and_suppress_repeated_notifications(self) -> None:
        for rule in self.rules:
            with self.subTest(rule=rule["uid"]):
                self.assertRegex(rule["for"], r"^[1-9][0-9]*m$")
                self.assertEqual(rule["keepFiringFor"], "2m")
                self.assertFalse(rule["isPaused"])
                self.assertIn(rule["labels"]["severity"], {"warning", "critical"})
                notification = rule["notification_settings"]
                self.assertEqual(notification["receiver"], "FEELM Mattermost")
                self.assertEqual(notification["group_wait"], "30s")
                self.assertEqual(notification["group_interval"], "5m")
                self.assertEqual(notification["repeat_interval"], "4h")

    def test_log_alerts_exclude_their_own_grafana_explore_requests(self) -> None:
        for uid in ("feelm_critical_log_detected", "feelm_error_log_spike"):
            rule = next(rule for rule in self.rules if rule["uid"] == uid)
            loki_expression = rule["data"][0]["model"]["expr"]
            self.assertIn(
                '!~ "(?i)/grafana/.*'
                '(ERROR|Exception|FATAL|OutOfMemoryError|Unhandled exception)"',
                loki_expression,
            )

            panes = json.loads(
                parse_qs(urlparse(rule["annotations"]["explore_url"]).query)["panes"][0]
            )
            explore_expression = panes["logs"]["queries"][0]["expr"]
            self.assertIn(
                "!~ `(?i)/grafana/.*"
                "(ERROR|Exception|FATAL|OutOfMemoryError|Unhandled exception)`",
                explore_expression,
            )

    def test_mattermost_contact_point_uses_authenticated_internal_relay(self) -> None:
        self.assertNotIn("policies", self.payload)
        contact_point = self.payload["contactPoints"][0]
        self.assertEqual(contact_point["name"], "FEELM Mattermost")
        receiver = contact_point["receivers"][0]
        self.assertEqual(receiver["type"], "webhook")
        self.assertFalse(receiver["disableResolveMessage"])
        self.assertEqual(
            receiver["settings"]["url"],
            "http://mattermost-alert-relay:8080/alerts",
        )
        self.assertEqual(
            receiver["settings"]["hmacConfig"]["secret"],
            "$GRAFANA_ALERT_HMAC_SECRET",
        )
        self.assertEqual(
            receiver["settings"]["hmacConfig"]["timestampHeader"],
            "X-Grafana-Alerting-Timestamp",
        )
        self.assertNotIn("hooks/", ALERTING.read_text(encoding="utf-8"))
        self.assertEqual(self.payload["templates"][0]["name"], "feelm.mattermost")
        template = self.payload["templates"][0]["template"]
        self.assertIn("오류 로그 감지", template)
        self.assertIn("Grafana Explore에서 로그 확인", template)

    def test_relay_signing_secret_is_required_and_protected(self) -> None:
        compose = (ROOT / "infra/compose/monitoring.yml").read_text(encoding="utf-8")
        self.assertIn(
            "GRAFANA_ALERT_HMAC_SECRET: ${GRAFANA_ALERT_HMAC_SECRET:?"
            "GRAFANA_ALERT_HMAC_SECRET is required}",
            compose,
        )
        self.assertIn("MATTERMOST_PASSWORD: ${MATTERMOST_PASSWORD:?", compose)
        self.assertNotIn("GRAFANA_ALERT_WEBHOOK_URL", compose)

        env_example = (ROOT / "infra/.env.example").read_text(encoding="utf-8")
        self.assertIn("GRAFANA_ALERT_HMAC_SECRET=", env_example)
        self.assertNotIn("GRAFANA_ALERT_HMAC_SECRET=secret", env_example)

        contract = json.loads(
            (ROOT / "infra/config/required-ci-variables.json").read_text(encoding="utf-8")
        )
        entry = next(
            item for item in contract["variables"]
            if item["key"] == "GRAFANA_ALERT_HMAC_SECRET"
        )
        self.assertEqual(entry["environment_scope"], "service")
        self.assertTrue(entry["protected"])
        self.assertTrue(entry["masked"])
        self.assertTrue(entry["hidden"])
        self.assertTrue(entry["raw"])
        self.assertEqual(entry["format"], "nonempty")


if __name__ == "__main__":
    unittest.main()
