import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import yaml


SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "jira" / "create_issues.py"
SPEC = importlib.util.spec_from_file_location("create_issues", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class FakeClient:
    def __init__(self):
        self.config = MODULE.JiraConfig("https://example.atlassian.net", "TEST", "user", "token")
        self.created = []

    def create_issue(self, fields):
        self.created.append(fields)
        return f"TEST-{len(self.created)}"

    def resolve_issue_type_id(self, logical_name):
        return {"Epic": "10000", "Task": "10002"}[logical_name]

    def resolve_field_id(self, *names):
        return {"Story Points": "customfield_10016", "Sprint": "customfield_10020"}[names[0]]

    def resolve_assignee_account_id(self, query):
        return f"account:{query}"

    def get_active_sprint_id(self):
        return 54280

    def get_current_account_id(self):
        return "current-account"

    def get_project_epics(self, epic_type_id):
        self.requested_epic_type_id = epic_type_id
        return [{"key": "TEST-90", "fields": {"summary": "[협업] 협업 환경"}}]


class FakeResponse:
    def __init__(self, payload):
        self.payload = payload

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self):
        return json.dumps(self.payload).encode()


class CreateIssuesTest(unittest.TestCase):
    def test_apply_records_keys_and_resolves_local_epic(self):
        plan = {
            "tasks": [
                {
                    "id": "parent",
                    "issue_type": "Epic",
                    "summary": "Epic",
                    "description": "Parent",
                    "done_when": ["Done"],
                    "jira_key": None,
                },
                {
                    "id": "child",
                    "summary": "Task",
                    "description": "Child",
                    "epic": "parent",
                    "story_points": 2,
                    "done_when": ["Tested"],
                    "jira_key": None,
                },
            ]
        }
        MODULE.validate_tasks(plan["tasks"])
        client = FakeClient()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "tasks.yml"
            MODULE.save_plan(path, plan)
            self.assertEqual(MODULE.apply_plan(path, plan, client), 2)
            saved = yaml.safe_load(path.read_text(encoding="utf-8"))

        self.assertEqual(saved["tasks"][0]["jira_key"], "TEST-1")
        self.assertEqual(saved["tasks"][1]["jira_key"], "TEST-2")
        self.assertEqual(client.created[0]["issuetype"], {"id": "10000"})
        self.assertEqual(client.created[1]["issuetype"], {"id": "10002"})
        self.assertEqual(client.created[1]["parent"], {"key": "TEST-1"})

    def test_existing_key_is_not_created_again(self):
        plan = {
            "tasks": [
                {
                    "id": "done",
                    "summary": "Existing",
                    "description": "Existing task",
                    "done_when": ["Done"],
                    "jira_key": "TEST-7",
                }
            ]
        }
        self.assertEqual(MODULE.pending_tasks(plan), [])

    def test_duplicate_id_is_rejected(self):
        task = {
            "id": "same",
            "summary": "Task",
            "description": "Description",
            "done_when": ["Done"],
        }
        with self.assertRaises(MODULE.PlanError):
            MODULE.validate_tasks([task, task.copy()])

    def test_local_epic_must_be_declared_first(self):
        child = {
            "id": "child",
            "summary": "Task",
            "description": "Child",
            "epic": "parent",
            "done_when": ["Done"],
        }
        parent = {
            "id": "parent",
            "issue_type": "Epic",
            "summary": "Epic",
            "description": "Parent",
            "done_when": ["Done"],
        }
        with self.assertRaises(MODULE.PlanError):
            MODULE.validate_tasks([child, parent])

    def test_summary_and_description_must_be_strings(self):
        task = {
            "id": "invalid",
            "summary": ["not", "text"],
            "description": 123,
            "done_when": ["Done"],
        }
        with self.assertRaises(MODULE.PlanError):
            MODULE.validate_tasks([task])

    def test_local_epic_reference_must_point_to_epic(self):
        parent = {
            "id": "not-epic",
            "summary": "Task",
            "description": "Parent candidate",
            "done_when": ["Done"],
        }
        child = {
            "id": "child",
            "summary": "Child",
            "description": "Child task",
            "epic": "not-epic",
            "done_when": ["Done"],
        }
        with self.assertRaises(MODULE.PlanError):
            MODULE.validate_tasks([parent, child])

    def test_jira_key_format_is_validated(self):
        task = {
            "id": "done",
            "summary": "Existing",
            "description": "Existing task",
            "done_when": ["Done"],
            "jira_key": "invalid-key",
        }
        with self.assertRaises(MODULE.PlanError):
            MODULE.validate_tasks([task])

    def test_check_does_not_require_plan_file(self):
        args = MODULE.parse_args(["--check"])
        self.assertTrue(args.check)
        self.assertIsNone(args.file)

    def test_connection_check_is_read_only(self):
        config = MODULE.JiraConfig("https://example.atlassian.net", "TEST", "user", "token")
        client = MODULE.JiraClient(config)
        responses = [FakeResponse({"accountId": "hidden"}), FakeResponse({"issueTypes": [{"id": "1", "name": "Task"}]})]
        with patch.object(MODULE, "urlopen", side_effect=responses) as mocked:
            self.assertEqual(client.check_connection(), [{"id": "1", "name": "Task"}])
        self.assertEqual([call.args[0].method for call in mocked.call_args_list], ["GET", "GET"])

    def test_korean_issue_type_names_are_resolved(self):
        config = MODULE.JiraConfig("https://example.atlassian.net", "TEST", "user", "token")
        client = MODULE.JiraClient(config)
        client._issue_types = [{"id": "10000", "name": "에픽"}, {"id": "10002", "name": "작업"}]
        self.assertEqual(client.resolve_issue_type_id("Epic"), "10000")
        self.assertEqual(client.resolve_issue_type_id("Task"), "10002")

    def test_optional_fields_are_resolved_and_created(self):
        plan = {
            "tasks": [
                {
                    "id": "extended",
                    "summary": "[협업] Extended",
                    "description": "Fields",
                    "done_when": ["Done"],
                    "story_points": 3,
                },
                {
                    "id": "plain",
                    "summary": "[협업] Plain",
                    "description": "No optional fields",
                    "done_when": ["Done"],
                    "story_points": 1,
                },
            ]
        }
        client = FakeClient()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "tasks.yml"
            MODULE.save_plan(path, plan)
            MODULE.apply_plan(path, plan, client)
        fields = client.created[0]
        self.assertEqual(fields["customfield_10016"], 3)
        self.assertEqual(fields["assignee"], {"accountId": "current-account"})
        self.assertEqual(fields["customfield_10020"], 54280)
        self.assertEqual(fields["parent"], {"key": "TEST-90"})
        self.assertIn("customfield_10016", client.created[1])
        self.assertIn("customfield_10020", client.created[1])

    def test_invalid_story_points_and_manual_automatic_fields_are_rejected(self):
        base = {
            "id": "invalid",
            "summary": "Invalid",
            "description": "Invalid fields",
            "done_when": ["Done"],
        }
        with self.assertRaises(MODULE.PlanError):
            MODULE.validate_tasks([{**base, "story_points": -1}])
        with self.assertRaises(MODULE.PlanError):
            MODULE.validate_tasks([{**base, "sprint": "active"}])
        with self.assertRaises(MODULE.PlanError):
            MODULE.validate_tasks([{**base, "assignee": "someone"}])

    def test_active_sprint_requires_board_id(self):
        client = MODULE.JiraClient(
            MODULE.JiraConfig("https://example.atlassian.net", "TEST", "user", "token")
        )
        with self.assertRaises(MODULE.PlanError):
            client.get_active_sprint_id()

    def test_field_and_current_user_resolution(self):
        client = MODULE.JiraClient(
            MODULE.JiraConfig("https://example.atlassian.net", "TEST", "user", "token")
        )
        client._fields = [{"id": "customfield_1", "name": "Story Points"}]
        self.assertEqual(client.resolve_field_id("Story Points", "스토리 포인트"), "customfield_1")
        with patch.object(client, "request", return_value={"accountId": "abc"}):
            self.assertEqual(client.get_current_account_id(), "abc")

    def test_pending_task_requires_ai_estimated_story_points(self):
        task = {
            "id": "estimated",
            "summary": "[백엔드] API 연동",
            "description": "DB schema와 API를 변경한다.",
            "done_when": ["테스트한다.", "문서화한다."],
        }
        with self.assertRaises(MODULE.PlanError):
            MODULE.validate_tasks([task])
        MODULE.validate_tasks([{**task, "story_points": 12}])

    def test_epic_selection_prefers_matching_area(self):
        task = {
            "id": "task",
            "summary": "[협업] Jira 자동화",
            "description": "협업 도구를 개선한다.",
            "done_when": ["완료"],
        }
        epics = [
            {"key": "TEST-1", "fields": {"summary": "[인프라] 배포 환경"}},
            {"key": "TEST-2", "fields": {"summary": "[협업] 프로젝트 협업 환경"}},
        ]
        self.assertEqual(MODULE.select_related_epic(task, epics), "TEST-2")


if __name__ == "__main__":
    unittest.main()
