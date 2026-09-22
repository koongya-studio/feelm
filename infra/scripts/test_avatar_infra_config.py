from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]


class AvatarInfraConfigTests(unittest.TestCase):
    def test_compose_shares_one_host_directory_with_expected_access(self):
        compose = (ROOT / "infra/compose/service.yml").read_text(encoding="utf-8")

        self.assertIn(
            "${AVATAR_HOST_PATH:-/srv/feelm/data/avatars}:/var/www/avatars:ro",
            compose,
        )
        self.assertIn(
            "${AVATAR_HOST_PATH:-/srv/feelm/data/avatars}:/data/avatars",
            compose,
        )
        self.assertIn("AVATAR_STORAGE_PATH: /data/avatars", compose)
        self.assertIn(
            "AVATAR_PUBLIC_URL_PREFIX: ${AVATAR_PUBLIC_URL_PREFIX:-/media/avatars}",
            compose,
        )

    def test_nginx_serves_only_reads_with_cache_and_upload_headroom(self):
        nginx = (ROOT / "infra/nginx/nginx.conf").read_text(encoding="utf-8")

        self.assertIn("client_max_body_size 11m;", nginx)
        self.assertIn("location /media/avatars/ {", nginx)
        self.assertIn("alias /var/www/avatars/;", nginx)
        self.assertIn("limit_except GET HEAD", nginx)
        self.assertIn('add_header Cache-Control "public, max-age=86400" always;', nginx)
        self.assertIn('add_header X-Content-Type-Options "nosniff" always;', nginx)


if __name__ == "__main__":
    unittest.main()
