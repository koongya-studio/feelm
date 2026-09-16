import importlib.util
import io
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("notify-deployment.py")
SPEC = importlib.util.spec_from_file_location("notify_deployment", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class Response:
    status = 200

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False


class NotifyDeploymentTest(unittest.TestCase):
    def setUp(self):
        self.environ = {
            "MATTERMOST_DEPLOY_WEBHOOK_URL": "https://mattermost.example/hooks/secret-value",
            "CI_ENVIRONMENT_NAME": "service",
            "CI_COMMIT_SHORT_SHA": "abc12345",
            "CI_JOB_URL": "https://gitlab.example/jobs/10",
            "CI_PIPELINE_URL": "https://gitlab.example/pipelines/20",
        }

    def test_posts_success_message_with_deployment_links(self):
        with patch.object(MODULE, "urlopen", return_value=Response()) as request:
            self.assertTrue(MODULE.notify("backend", "success", self.environ))

        sent = request.call_args.args[0]
        payload = json.loads(sent.data.decode("utf-8"))
        self.assertEqual(sent.full_url, self.environ["MATTERMOST_DEPLOY_WEBHOOK_URL"])
        self.assertIn("backend 배포 성공", payload["text"])
        self.assertIn("`abc12345`", payload["text"])
        self.assertIn(self.environ["CI_JOB_URL"], payload["text"])
        self.assertIn(self.environ["CI_PIPELINE_URL"], payload["text"])
        self.assertEqual(request.call_args.kwargs["timeout"], 10)

    def test_failure_message_identifies_failed_service(self):
        message = MODULE.build_message("consumer", "failed", self.environ)
        self.assertIn("consumer 배포 실패", message)
        self.assertIn("환경: `service`", message)

    def test_missing_webhook_warns_without_failing_deployment(self):
        stderr = io.StringIO()
        with patch.object(MODULE, "urlopen") as request, patch("sys.stderr", stderr):
            self.assertEqual(MODULE.run(["frontend", "success"], {}), 0)
        request.assert_not_called()
        self.assertIn("MATTERMOST_DEPLOY_WEBHOOK_URL", stderr.getvalue())

    def test_delivery_error_does_not_expose_webhook_or_fail_deployment(self):
        stderr = io.StringIO()
        with patch.object(MODULE, "urlopen", side_effect=MODULE.URLError("secret detail")), patch(
            "sys.stderr", stderr
        ):
            self.assertEqual(MODULE.run(["backend", "failed"], self.environ), 0)
        output = stderr.getvalue()
        self.assertIn("전송 실패", output)
        self.assertNotIn("secret-value", output)
        self.assertNotIn("secret detail", output)

    def test_rejects_non_https_and_credential_bearing_urls(self):
        for value in (
            "http://mattermost.example/hooks/value",
            "https://user:password@mattermost.example/hooks/value",
            "https://mattermost.example/hooks/value#fragment",
        ):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    MODULE.https_webhook_url(value)

    def test_untrusted_values_cannot_create_mentions_or_markdown(self):
        environ = {**self.environ, "CI_COMMIT_SHORT_SHA": "abc @channel **boom**"}
        message = MODULE.build_message("backend", "success", environ)
        self.assertNotIn("@channel", message)
        self.assertNotIn("**boom**", message)


if __name__ == "__main__":
    unittest.main()
