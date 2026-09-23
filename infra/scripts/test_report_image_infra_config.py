from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]


class ReportImageInfraConfigTests(unittest.TestCase):
    def test_backend_alone_mounts_private_report_image_directory(self):
        compose = (ROOT / "infra/compose/service.yml").read_text(encoding="utf-8")

        self.assertIn(
            "${REPORT_IMAGE_HOST_PATH:-/srv/feelm/data/report-images}:/data/report-images",
            compose,
        )
        self.assertIn("REPORT_IMAGE_STORAGE_PATH: /data/report-images", compose)
        self.assertIn(
            "REPORT_IMAGE_SIGNING_SECRET: "
            "${REPORT_IMAGE_SIGNING_SECRET:?REPORT_IMAGE_SIGNING_SECRET is required}",
            compose,
        )
        self.assertNotIn("/var/www/report-images", compose)

    def test_nginx_has_no_static_report_image_location(self):
        nginx = (ROOT / "infra/nginx/nginx.conf").read_text(encoding="utf-8")

        self.assertNotIn("location /media/report-images", nginx)
        self.assertNotIn("alias /var/www/report-images", nginx)
        upload = nginx.split(
            "location ~ ^/api/v1/reports/[1-9][0-9]*/image$ {", 1
        )[1].split("\n        }", 1)[0]
        common_api = nginx.split("location /api/ {", 1)[1].split("\n        }", 1)[0]
        self.assertIn("client_max_body_size 26m;", upload)
        self.assertIn("client_max_body_size 11m;", common_api)
        self.assertNotIn("client_max_body_size 26m;", common_api)

if __name__ == "__main__":
    unittest.main()
