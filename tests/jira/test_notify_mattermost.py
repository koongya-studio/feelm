import importlib.util
import os
import sys
import unittest
from datetime import date
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "jira" / "notify_mattermost.py"
SPEC = importlib.util.spec_from_file_location("notify_mattermost", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def issue(key, summary, assignee=None, category="new"):
    return {
        "key": key,
        "fields": {
            "summary": summary,
            "assignee": ({"displayName": assignee} if assignee else None),
            "status": {"statusCategory": {"key": category}},
        },
    }


class Headers(dict):
    pass


class NotifyMattermostTest(unittest.TestCase):
    def test_jira_search_uses_active_sprint_and_paginates(self):
        config = MODULE.JiraConfig("https://jira.example", "TEST", "user", "token")
        client = MODULE.JiraClient(config)
        responses = [
            ({"issues": [issue("TEST-1", "One")], "nextPageToken": "next"}, Headers()),
            ({"issues": [issue("TEST-2", "Two")], "isLast": True}, Headers()),
        ]
        with patch.object(MODULE, "api_request", side_effect=responses) as request:
            result = client.unfinished_active_sprint_issues()
        self.assertEqual([item["key"] for item in result], ["TEST-1", "TEST-2"])
        first_payload = request.call_args_list[0].kwargs["payload"]
        second_payload = request.call_args_list[1].kwargs["payload"]
        self.assertIn("sprint in openSprints()", first_payload["jql"])
        self.assertIn("statusCategory != Done", first_payload["jql"])
        self.assertEqual(second_payload["nextPageToken"], "next")

    def test_repeated_jira_page_token_fails(self):
        config = MODULE.JiraConfig("https://jira.example", "TEST", "user", "token")
        response = ({"issues": [], "nextPageToken": "same"}, Headers())
        with patch.object(MODULE, "api_request", side_effect=[response, response]):
            with self.assertRaisesRegex(MODULE.DigestError, "페이지 토큰"):
                MODULE.JiraClient(config).unfinished_active_sprint_issues()

    def test_message_groups_status_and_places_unassigned_last(self):
        message = MODULE.build_message(
            [
                issue("TEST-2", "Todo", "가나다", "new"),
                issue("TEST-1", "Working", "가나다", "indeterminate"),
                issue("TEST-3", "No owner", None, "new"),
            ],
            "https://jira.example",
            date(2026, 9, 16),
        )
        self.assertIn("2026-09-16", message)
        self.assertIn("활성 스프린트 미완료 작업: **3개**", message)
        self.assertIn("**진행 중** (1)", message)
        self.assertIn("[TEST-1](https://jira.example/browse/TEST-1) Working", message)
        self.assertLess(message.index("#### 가나다"), message.index("#### 미할당"))

    def test_empty_message_is_explicit(self):
        message = MODULE.build_message([], "https://jira.example", date(2026, 9, 16))
        self.assertIn("완료되지 않은 작업이 없습니다", message)

    def test_long_message_is_bounded(self):
        issues = [issue(f"TEST-{index}", "x" * 500, "owner") for index in range(100)]
        message = MODULE.build_message(issues, "https://jira.example")
        self.assertLessEqual(len(message), MODULE.MAX_MESSAGE_LENGTH)
        self.assertIn("일부 작업이 생략", message)

    def test_mattermost_login_resolve_post_and_logout(self):
        config = MODULE.MattermostConfig(
            "https://mm.example", "user", "password", None, "team", "channel"
        )
        client = MODULE.MattermostClient(config)
        responses = [
            ({"id": "user-id"}, Headers(Token="session")),
            ({"id": "team-id"}, Headers()),
            ({"id": "channel-id"}, Headers()),
            ({"id": "post-id"}, Headers()),
            (None, Headers()),
        ]
        with patch.object(MODULE, "api_request", side_effect=responses) as request:
            token = client.login()
            channel_id = client.resolve_channel_id(token)
            client.post(token, channel_id, "message")
            client.logout(token)
        self.assertEqual(token, "session")
        self.assertEqual(channel_id, "channel-id")
        self.assertEqual(request.call_args_list[0].kwargs["payload"]["password"], "password")
        self.assertTrue(request.call_args_list[-1].args[0].endswith("/api/v4/users/logout"))

    def test_channel_id_skips_name_resolution(self):
        config = MODULE.MattermostConfig(
            "https://mm.example", "user", "password", "direct-id", None, None
        )
        with patch.object(MODULE, "api_request") as request:
            self.assertEqual(MODULE.MattermostClient(config).resolve_channel_id("token"), "direct-id")
        request.assert_not_called()

    def test_mattermost_config_requires_a_channel_selector(self):
        values = {
            "MATTERMOST_BASE_URL": "https://mm.example",
            "MATTERMOST_LOGIN_ID": "user",
            "MATTERMOST_PASSWORD": "password",
        }
        with patch.dict(os.environ, values, clear=True):
            with self.assertRaisesRegex(MODULE.DigestError, "MATTERMOST_CHANNEL_ID"):
                MODULE.load_mattermost_config()

    def test_base_urls_must_use_https(self):
        with patch.dict(os.environ, {"JIRA_BASE_URL": "http://jira.example"}, clear=True):
            with self.assertRaisesRegex(MODULE.DigestError, "HTTPS"):
                MODULE.https_base_url("JIRA_BASE_URL")

    def test_run_logs_out_when_posting_fails(self):
        class FakeJiraClient:
            def __init__(self, _config):
                pass

            def unfinished_active_sprint_issues(self):
                return []

        class FakeMattermostClient:
            logged_out = False

            def __init__(self, _config):
                pass

            def login(self):
                return "temporary-token"

            def resolve_channel_id(self, _token):
                return "channel-id"

            def post(self, _token, _channel_id, _message):
                raise MODULE.DigestError("posting failed")

            def logout(self, token):
                type(self).logged_out = token == "temporary-token"

        jira_config = MODULE.JiraConfig("https://jira.example", "TEST", "user", "token")
        mattermost_config = MODULE.MattermostConfig(
            "https://mm.example", "user", "password", "channel-id", None, None
        )
        with (
            patch.object(MODULE, "load_dotenv"),
            patch.object(MODULE, "load_jira_config", return_value=jira_config),
            patch.object(MODULE, "load_mattermost_config", return_value=mattermost_config),
            patch.object(MODULE, "JiraClient", FakeJiraClient),
            patch.object(MODULE, "MattermostClient", FakeMattermostClient),
            patch.object(MODULE.sys, "stderr"),
        ):
            self.assertEqual(MODULE.run([]), 1)
        self.assertTrue(FakeMattermostClient.logged_out)


if __name__ == "__main__":
    unittest.main()
