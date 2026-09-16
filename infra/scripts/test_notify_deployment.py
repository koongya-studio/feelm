import importlib.util
import io
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


class NotifyDeploymentTest(unittest.TestCase):
    def setUp(self):
        self.environ = {
            "MATTERMOST_BASE_URL": "https://mattermost.example",
            "MATTERMOST_LOGIN_ID": "deploy-notifier",
            "MATTERMOST_PASSWORD": "secret-password",
            "MATTERMOST_CHANNEL_ID": "channel-id",
            "CI_ENVIRONMENT_NAME": "service",
            "CI_COMMIT_SHORT_SHA": "abc12345",
            "CI_JOB_URL": "https://gitlab.example/jobs/10",
            "CI_PIPELINE_URL": "https://gitlab.example/pipelines/20",
        }

    def test_logs_in_posts_success_message_and_logs_out(self):
        responses = [
            ({"id": "user-id"}, {"Token": "session-secret"}),
            ({"id": "post-id"}, {}),
            (None, {}),
        ]
        with patch.object(MODULE, "api_request", side_effect=responses) as request:
            self.assertTrue(MODULE.notify("backend", "success", self.environ))

        self.assertTrue(request.call_args_list[0].args[0].endswith("/api/v4/users/login"))
        post = request.call_args_list[1]
        self.assertTrue(post.args[0].endswith("/api/v4/posts"))
        self.assertEqual(post.kwargs["payload"]["channel_id"], "channel-id")
        message = post.kwargs["payload"]["message"]
        self.assertIn("backend 배포 성공", message)
        self.assertIn("`abc12345`", message)
        self.assertIn(self.environ["CI_JOB_URL"], message)
        self.assertTrue(request.call_args_list[2].args[0].endswith("/api/v4/users/logout"))

    def test_failure_message_identifies_failed_service(self):
        message = MODULE.build_message("consumer", "failed", self.environ)
        self.assertIn("consumer 배포 실패", message)
        self.assertIn("환경: `service`", message)

    def test_missing_account_config_warns_without_failing_deployment(self):
        stderr = io.StringIO()
        with patch.object(MODULE, "api_request") as request, patch("sys.stderr", stderr):
            self.assertEqual(MODULE.run(["frontend", "success"], {}), 0)
        request.assert_not_called()
        self.assertIn("전송 실패", stderr.getvalue())

    def test_delivery_error_logs_out_without_exposing_credentials_or_failing_deployment(self):
        stderr = io.StringIO()
        responses = [
            ({"id": "user-id"}, {"Token": "session-secret"}),
            MODULE.DeploymentNotificationError("secret-password session-secret"),
            (None, {}),
        ]
        with patch.object(MODULE, "api_request", side_effect=responses) as request, patch(
            "sys.stderr", stderr
        ):
            self.assertEqual(MODULE.run(["backend", "failed"], self.environ), 0)
        output = stderr.getvalue()
        self.assertIn("전송 실패", output)
        self.assertNotIn("secret-password", output)
        self.assertNotIn("session-secret", output)
        self.assertTrue(request.call_args_list[-1].args[0].endswith("/api/v4/users/logout"))

    def test_logout_error_does_not_change_successful_notification(self):
        responses = [
            ({"id": "user-id"}, {"Token": "session-secret"}),
            ({"id": "post-id"}, {}),
            MODULE.DeploymentNotificationError("logout secret"),
        ]
        stderr = io.StringIO()
        with patch.object(MODULE, "api_request", side_effect=responses), patch(
            "sys.stderr", stderr
        ):
            self.assertTrue(MODULE.notify("consumer", "success", self.environ))
        self.assertIn("로그아웃 실패", stderr.getvalue())
        self.assertNotIn("logout secret", stderr.getvalue())

    def test_untrusted_values_cannot_create_mentions_or_markdown(self):
        environ = {**self.environ, "CI_COMMIT_SHORT_SHA": "abc @channel **boom**"}
        message = MODULE.build_message("backend", "success", environ)
        self.assertNotIn("@channel", message)
        self.assertNotIn("**boom**", message)


if __name__ == "__main__":
    unittest.main()
