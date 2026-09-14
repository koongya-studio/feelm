import re
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
NGINX_CONFIG = REPOSITORY_ROOT / "infra" / "nginx" / "nginx.conf"
SERVICE_COMPOSE = REPOSITORY_ROOT / "infra" / "compose" / "service.yml"
DEPLOY_SCRIPT = REPOSITORY_ROOT / "infra" / "scripts" / "deploy-backend.sh"
ROLLBACK_SCRIPT = REPOSITORY_ROOT / "infra" / "scripts" / "rollback-backend.sh"
BACKEND_CONFIG = REPOSITORY_ROOT / "backend" / "src" / "main" / "resources" / "application.yml"
BACKEND_PROXY_PATHS = ("/swagger-ui.html", "/swagger-ui/", "/v3/api-docs", "/api/")


def location_body(config: str, path: str) -> str:
    match = re.search(
        rf"location\s+(?:=\s+)?{re.escape(path)}\s*\{{(?P<body>.*?)\n\s*\}}",
        config,
        re.DOTALL,
    )
    if match is None:
        raise AssertionError(f"Nginx location이 없습니다: {path}")
    return match.group("body")


class SwaggerAccessPolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.nginx = NGINX_CONFIG.read_text(encoding="utf-8")
        cls.compose = SERVICE_COMPOSE.read_text(encoding="utf-8")
        cls.deploy_script = DEPLOY_SCRIPT.read_text(encoding="utf-8")
        cls.rollback_script = ROLLBACK_SCRIPT.read_text(encoding="utf-8")
        cls.backend_config = BACKEND_CONFIG.read_text(encoding="utf-8")

    def test_every_swagger_entry_point_requires_team_basic_auth(self) -> None:
        for path in ("/swagger-ui.html", "/swagger-ui/", "/v3/api-docs"):
            with self.subTest(path=path):
                body = location_body(self.nginx, path)
                self.assertRegex(body, r"\bauth_basic\s+[^;]+;")
                self.assertRegex(
                    body,
                    r"\bauth_basic_user_file\s+/run/secrets/swagger_htpasswd\s*;",
                )
                self.assertRegex(body, r"\bproxy_pass\s+http://backend:8080(?:/)?\s*;")

    def test_swagger_credentials_are_scoped_away_from_product_api(self) -> None:
        api_body = location_body(self.nginx, "/api/")
        self.assertNotRegex(api_body, r"\bauth_basic\b")
        self.assertRegex(api_body, r"\bproxy_pass\s+http://backend:8080(?:/)?\s*;")

    def test_backend_proxy_replaces_client_supplied_forwarding_headers(self) -> None:
        for path in BACKEND_PROXY_PATHS:
            with self.subTest(path=path):
                body = location_body(self.nginx, path)
                self.assertRegex(body, r"\bproxy_set_header\s+X-Real-IP\s+\$remote_addr\s*;")
                self.assertRegex(body, r"\bproxy_set_header\s+X-Forwarded-For\s+\$remote_addr\s*;")
                self.assertRegex(body, r'\bproxy_set_header\s+X-Forwarded-Host\s+""\s*;')
                self.assertRegex(body, r'\bproxy_set_header\s+X-Forwarded-Port\s+""\s*;')

    def test_backend_trusts_the_compose_bridge_range(self) -> None:
        self.assertRegex(
            self.backend_config,
            r"(?m)^\s+internal-proxies:\s*127\.0\.0\.0/8,::1/128,172\.16\.0\.0/12\s*$",
        )

    def test_team_password_file_is_read_only_and_not_stored_in_repository(self) -> None:
        self.assertIn(
            "${SWAGGER_HTPASSWD_PATH:-/dev/null}:/run/secrets/swagger_htpasswd:ro",
            self.compose,
        )
        self.assertFalse((REPOSITORY_ROOT / "infra" / "nginx" / ".htpasswd").exists())

    def test_backend_has_no_host_port_that_bypasses_nginx(self) -> None:
        backend_block = re.search(
            r"(?ms)^  backend:\n(?P<body>.*?)(?=^  [a-zA-Z0-9_-]+:|^volumes:)",
            self.compose,
        )
        self.assertIsNotNone(backend_block)
        self.assertNotRegex(backend_block.group("body"), r"(?m)^\s+ports:\s*$")

    def test_deployment_scripts_do_not_use_backend_host_port(self) -> None:
        for name, script in (
            ("deploy", self.deploy_script),
            ("rollback", self.rollback_script),
        ):
            with self.subTest(script=name):
                self.assertNotIn("http://127.0.0.1:8080", script)
                self.assertIn("docker exec feelm-backend", script)


if __name__ == "__main__":
    unittest.main()
