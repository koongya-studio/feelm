import re
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
NGINX_CONFIG = REPOSITORY_ROOT / "infra" / "nginx" / "nginx.conf"
SERVICE_COMPOSE = REPOSITORY_ROOT / "infra" / "compose" / "service.yml"
MONITORING_COMPOSE = REPOSITORY_ROOT / "infra" / "compose" / "monitoring.yml"
ENV_EXAMPLE = REPOSITORY_ROOT / "infra" / ".env.example"


def location_body(config: str, path: str) -> str:
    match = re.search(
        rf"location\s+(?:=\s+)?{re.escape(path)}\s*\{{(?P<body>.*?)\n\s*\}}",
        config,
        re.DOTALL,
    )
    if match is None:
        raise AssertionError(f"Nginx location이 없습니다: {path}")
    return match.group("body")


def service_body(compose: str, service: str) -> str:
    match = re.search(
        rf"(?ms)^  {re.escape(service)}:\n(?P<body>.*?)(?=^  [a-zA-Z0-9_-]+:|^volumes:)",
        compose,
    )
    if match is None:
        raise AssertionError(f"Compose service가 없습니다: {service}")
    return match.group("body")


class GrafanaAccessPolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.nginx = NGINX_CONFIG.read_text(encoding="utf-8")
        cls.service_compose = SERVICE_COMPOSE.read_text(encoding="utf-8")
        cls.monitoring_compose = MONITORING_COMPOSE.read_text(encoding="utf-8")
        cls.env_example = ENV_EXAMPLE.read_text(encoding="utf-8")

    def test_grafana_path_redirects_to_canonical_trailing_slash(self) -> None:
        body = location_body(self.nginx, "/grafana")
        self.assertRegex(body, r"\breturn\s+308\s+/grafana/\s*;")

    def test_grafana_proxy_requires_dedicated_basic_auth(self) -> None:
        body = location_body(self.nginx, "/grafana/")
        self.assertRegex(body, r"\bauth_basic\s+[^;]+;")
        self.assertRegex(
            body,
            r"\bauth_basic_user_file\s+/run/secrets/grafana_htpasswd\s*;",
        )
        self.assertIn("set $grafana_upstream http://grafana:3000;", body)
        self.assertRegex(body, r"\bproxy_pass\s+\$grafana_upstream\s*;")

    def test_grafana_proxy_replaces_untrusted_forwarding_headers(self) -> None:
        body = location_body(self.nginx, "/grafana/")
        self.assertRegex(body, r"\bproxy_set_header\s+X-Real-IP\s+\$remote_addr\s*;")
        self.assertRegex(body, r"\bproxy_set_header\s+X-Forwarded-For\s+\$remote_addr\s*;")
        self.assertRegex(body, r'\bproxy_set_header\s+X-Forwarded-Host\s+""\s*;')
        self.assertRegex(body, r'\bproxy_set_header\s+X-Forwarded-Port\s+""\s*;')
        self.assertRegex(body, r"\bproxy_set_header\s+X-Forwarded-Proto\s+\$scheme\s*;")

    def test_grafana_live_websocket_headers_are_forwarded(self) -> None:
        body = location_body(self.nginx, "/grafana/")
        self.assertRegex(body, r"\bproxy_http_version\s+1\.1\s*;")
        self.assertRegex(body, r"\bproxy_read_timeout\s+3600s\s*;")
        self.assertRegex(body, r"\bproxy_send_timeout\s+3600s\s*;")
        self.assertRegex(body, r"\bproxy_set_header\s+Upgrade\s+\$http_upgrade\s*;")
        self.assertRegex(body, r"\bproxy_set_header\s+Connection\s+\$connection_upgrade\s*;")
        self.assertRegex(
            self.nginx,
            r"map\s+\$http_upgrade\s+\$connection_upgrade\s*\{",
        )

    def test_grafana_upstream_resolution_is_deferred(self) -> None:
        self.assertRegex(
            self.nginx,
            r"\bresolver\s+127\.0\.0\.11\s+valid=30s\s+ipv6=off\s*;",
        )

    def test_basic_auth_file_is_read_only_and_not_committed(self) -> None:
        self.assertIn(
            "${GRAFANA_HTPASSWD_PATH:-/dev/null}:/run/secrets/grafana_htpasswd:ro",
            self.service_compose,
        )
        self.assertFalse(
            (REPOSITORY_ROOT / "infra" / "nginx" / "grafana.htpasswd").exists()
        )
        self.assertIn("GRAFANA_HTPASSWD_PATH=", self.env_example)

    def test_nginx_does_not_log_auth_headers(self) -> None:
        self.assertNotIn("$http_authorization", self.nginx.lower())

    def test_grafana_uses_https_sub_path_and_keeps_its_own_login(self) -> None:
        grafana = service_body(self.monitoring_compose, "grafana")
        self.assertIn(
            "GF_SERVER_ROOT_URL: ${GRAFANA_ROOT_URL:-https://j15e106.p.ssafy.io/grafana/}",
            grafana,
        )
        self.assertIn(
            "GF_SERVER_SERVE_FROM_SUB_PATH: ${GRAFANA_SERVE_FROM_SUB_PATH:-true}",
            grafana,
        )
        self.assertIn(
            "GRAFANA_ROOT_URL=https://j15e106.p.ssafy.io/grafana/", self.env_example
        )
        self.assertIn("GRAFANA_SERVE_FROM_SUB_PATH=true", self.env_example)
        self.assertIn('GF_AUTH_ANONYMOUS_ENABLED: "false"', grafana)
        self.assertIn('GF_USERS_ALLOW_SIGN_UP: "false"', grafana)
        self.assertRegex(
            grafana,
            r"GF_SECURITY_ADMIN_PASSWORD:\s+\$\{GRAFANA_ADMIN_PASSWORD:\?[^}]+\}",
        )

    def test_monitoring_ports_remain_loopback_only(self) -> None:
        grafana = service_body(self.monitoring_compose, "grafana")
        prometheus = service_body(self.monitoring_compose, "prometheus")
        self.assertIn('"127.0.0.1:${GRAFANA_PORT:-3000}:3000"', grafana)
        self.assertIn('"127.0.0.1:${PROMETHEUS_PORT:-9090}:9090"', prometheus)
        self.assertNotIn("prometheus:9090", self.nginx)


if __name__ == "__main__":
    unittest.main()
