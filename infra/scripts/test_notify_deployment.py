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
            "CI_API_V4_URL": "https://gitlab.example/api/v4",
            "CI_PROJECT_ID": "30",
            "CI_PIPELINE_ID": "20",
            "CI_JOB_ID": "10",
            "CI_JOB_TOKEN": "gitlab-job-token",
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
        self.assertTrue(message.startswith("## 🚀 [DEPLOY] 배포 결과"))
        self.assertIn("> ✅ 성공 · 📦 backend · 🌐 service", message)
        self.assertIn("**🔎 배포 정보**", message)
        self.assertIn("`abc12345`", message)
        self.assertIn(self.environ["CI_JOB_URL"], message)
        self.assertTrue(request.call_args_list[2].args[0].endswith("/api/v4/users/logout"))

    def test_failure_message_identifies_failed_service(self):
        message = MODULE.build_message("redis", "failed", self.environ)
        self.assertIn("> ❌ 실패 · 📦 redis · 🌐 service", message)

    def test_cli_accepts_every_deployment_job_service(self):
        for service in ("backend", "frontend", "consumer", "redis", "monitoring"):
            with self.subTest(service=service):
                self.assertEqual(MODULE.parse_args([service, "failed"]).service, service)

        args = MODULE.parse_args(["pipeline-failure"])
        self.assertEqual(args.service, "pipeline-failure")
        self.assertIsNone(args.status)

    def test_pipeline_jobs_uses_job_token_and_paginates(self):
        responses = [
            ([{"id": 1, "name": "first"}], {"X-Next-Page": "2"}),
            ([{"id": 2, "name": "second"}], {"X-Next-Page": ""}),
        ]
        with patch.object(MODULE, "api_request", side_effect=responses) as request:
            jobs = MODULE.pipeline_jobs(self.environ)

        self.assertEqual([job["id"] for job in jobs], [1, 2])
        self.assertIn("/projects/30/pipelines/20/jobs", request.call_args_list[0].args[0])
        self.assertIn("page=1", request.call_args_list[0].args[0])
        self.assertIn("page=2", request.call_args_list[1].args[0])
        self.assertEqual(
            request.call_args_list[0].kwargs["headers"],
            {"JOB-TOKEN": "gitlab-job-token"},
        )

    def test_pipeline_jobs_rejects_repeated_page(self):
        with patch.object(
            MODULE,
            "api_request",
            return_value=([], {"X-Next-Page": "1"}),
        ):
            with self.assertRaisesRegex(MODULE.DeploymentNotificationError, "repeated"):
                MODULE.pipeline_jobs(self.environ)

    def test_predeploy_failures_exclude_deploy_current_and_allowed_failure_jobs(self):
        jobs = [
            {"id": 1, "name": "runner-check", "stage": "verify", "status": "failed"},
            {"id": 2, "name": "frontend-test", "stage": "test", "status": "failed"},
            {"id": 3, "name": "deploy-monitoring", "stage": "deploy", "status": "failed"},
            {"id": 4, "name": "optional", "stage": "test", "status": "failed",
             "allow_failure": True},
            {"id": 5, "name": "canceled", "stage": "test", "status": "canceled"},
            {"id": 10, "name": "notification", "stage": "notify", "status": "running"},
        ]
        failures = MODULE.failed_predeploy_jobs(jobs, "10")
        self.assertEqual([job["name"] for job in failures], ["frontend-test", "runner-check"])

    def test_pipeline_failure_posts_one_summary_but_success_stays_quiet(self):
        failures = [{
            "id": 1,
            "name": "ci-variable-preflight",
            "stage": "verify",
            "status": "failed",
            "web_url": "https://gitlab.example/jobs/1",
        }]
        with patch.object(MODULE, "pipeline_jobs", return_value=failures), \
             patch.object(MODULE, "post_message", return_value=True) as post:
            self.assertTrue(MODULE.notify_pipeline_failure(self.environ))
        message = post.call_args.args[0]
        self.assertIn("## 🚨 [CI] develop Pipeline 실패", message)
        self.assertIn("ci-variable-preflight", message)
        self.assertIn("https://gitlab.example/jobs/1", message)

        with patch.object(MODULE, "pipeline_jobs", return_value=[]), \
             patch.object(MODULE, "post_message") as post:
            self.assertFalse(MODULE.notify_pipeline_failure(self.environ))
        post.assert_not_called()

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
