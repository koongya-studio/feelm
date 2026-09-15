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
        self.automation_matches = {}
        self.summary_matches = {}
        self.existing_keys = set()

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

    def search_automation_id(self, automation_id):
        return self.automation_matches.get(automation_id, [])

    def search_exact_summary(self, summary):
        return self.summary_matches.get(summary, [])

    def issue_exists(self, jira_key):
        return jira_key in self.existing_keys


class FakeResponse:
    def __init__(self, payload):
        self.payload = payload

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self):
        return json.dumps(self.payload).encode()


class EmptyResponse(FakeResponse):
    def read(self):
        return b""


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
        self.assertRegex(client.created[0]["labels"][0], MODULE.AUTOMATION_ID_PATTERN)
        self.assertRegex(client.created[1]["labels"][0], MODULE.AUTOMATION_ID_PATTERN)

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

    def test_pending_task_story_points_must_use_complexity_scale(self):
        task = {
            "id": "invalid-story-points",
            "summary": "Task",
            "description": "Description",
            "story_points": 4,
            "done_when": ["Done"],
        }
        with self.assertRaisesRegex(MODULE.PlanError, "작업 복잡도"):
            MODULE.validate_tasks([task])

    def test_pending_task_accepts_each_story_point_value(self):
        for story_points in (1, 2, 3, 5, 8, 13, 21):
            with self.subTest(story_points=story_points):
                task = {
                    "id": f"story-points-{story_points}",
                    "summary": "Task",
                    "description": "Description",
                    "story_points": story_points,
                    "done_when": ["Done"],
                }
                MODULE.validate_tasks([task])

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

    def test_pending_task_requires_complexity_based_story_points(self):
        task = {
            "id": "estimated",
            "summary": "[백엔드] API 연동",
            "description": "DB schema와 API를 변경한다.",
            "done_when": ["테스트한다.", "문서화한다."],
        }
        with self.assertRaises(MODULE.PlanError):
            MODULE.validate_tasks([task])
        MODULE.validate_tasks([{**task, "story_points": 13}])

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

    def test_preview_assigns_and_persists_stable_automation_id(self):
        plan = {
            "tasks": [{
                "id": "new",
                "summary": "Task",
                "description": "Description",
                "story_points": 2,
                "done_when": ["Done"],
            }]
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "tasks.yml"
            MODULE.save_plan(path, plan)
            self.assertTrue(MODULE.ensure_automation_ids(path, plan))
            assigned = plan["tasks"][0]["automation_id"]
            self.assertRegex(assigned, MODULE.AUTOMATION_ID_PATTERN)
            self.assertFalse(MODULE.ensure_automation_ids(path, plan))
            saved = yaml.safe_load(path.read_text(encoding="utf-8"))
        self.assertEqual(saved["tasks"][0]["automation_id"], assigned)

    def test_existing_automation_id_is_reused(self):
        automation_id = "jira-auto-" + "a" * 32
        plan = {
            "tasks": [{
                "id": "retry",
                "summary": "Task",
                "description": "Description",
                "story_points": 2,
                "done_when": ["Done"],
                "automation_id": automation_id,
            }]
        }
        client = FakeClient()
        client.automation_matches[automation_id] = [{
            "key": "TEST-42",
            "fields": {"issuetype": {"name": "Task"}},
        }]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "tasks.yml"
            MODULE.save_plan(path, plan)
            self.assertEqual(MODULE.apply_plan(path, plan, client), 0)
        self.assertEqual(plan["tasks"][0]["jira_key"], "TEST-42")
        self.assertEqual(client.created, [])

    def test_duplicate_automation_id_in_jira_stops_creation(self):
        automation_id = "jira-auto-" + "b" * 32
        task = {
            "id": "duplicate",
            "summary": "Task",
            "description": "Description",
            "story_points": 2,
            "done_when": ["Done"],
            "automation_id": automation_id,
        }
        client = FakeClient()
        client.automation_matches[automation_id] = [
            {"key": "TEST-1", "fields": {}},
            {"key": "TEST-2", "fields": {}},
        ]
        with self.assertRaisesRegex(MODULE.PlanError, "중복된 Jira 업무"):
            MODULE.single_automation_match(task, client)

    def test_automatic_epic_is_reused_by_derived_automation_id(self):
        automation_id = "jira-auto-" + "c" * 32
        task = {
            "id": "child",
            "summary": "[협업] Child",
            "description": "Description",
            "story_points": 2,
            "done_when": ["Done"],
            "automation_id": automation_id,
        }
        client = FakeClient()
        client.automation_matches[f"{automation_id}-epic"] = [{
            "key": "TEST-88",
            "fields": {"issuetype": {"name": "Epic"}},
        }]
        self.assertEqual(MODULE.create_automatic_epic(task, client, "account"), "TEST-88")
        self.assertEqual(client.created, [])

    def test_exact_summary_candidate_requires_explicit_override(self):
        plan = {
            "tasks": [{
                "id": "manual-overlap",
                "summary": "Existing title",
                "description": "Description",
                "area": "협업",
                "story_points": 2,
                "done_when": ["Done"],
            }]
        }
        client = FakeClient()
        client.summary_matches["Existing title"] = [{"key": "TEST-7", "fields": {}}]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "tasks.yml"
            MODULE.save_plan(path, plan)
            with self.assertRaisesRegex(MODULE.PlanError, "같은 제목"):
                MODULE.apply_plan(path, plan, client)
            self.assertTrue(path.exists())
            self.assertEqual(MODULE.apply_plan(path, plan, client, True), 1)

    def test_partial_failure_keeps_plan_with_completed_key(self):
        class FailingClient(FakeClient):
            def create_issue(self, fields):
                if self.created:
                    raise MODULE.PlanError("second creation failed")
                return super().create_issue(fields)

        plan = {
            "tasks": [
                {
                    "id": "first",
                    "issue_type": "Epic",
                    "summary": "First",
                    "description": "Description",
                    "done_when": ["Done"],
                },
                {
                    "id": "second",
                    "issue_type": "Epic",
                    "summary": "Second",
                    "description": "Description",
                    "done_when": ["Done"],
                },
            ]
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "tasks.yml"
            MODULE.save_plan(path, plan)
            with self.assertRaisesRegex(MODULE.PlanError, "second creation failed"):
                MODULE.apply_plan(path, plan, FailingClient())
            saved = yaml.safe_load(path.read_text(encoding="utf-8"))
            self.assertTrue(path.exists())
        self.assertEqual(saved["tasks"][0]["jira_key"], "TEST-1")
        self.assertIsNone(saved["tasks"][1].get("jira_key"))

    def test_temporary_plan_is_deleted_only_under_configured_root(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / ".jira" / "tmp"
            root.mkdir(parents=True)
            allowed = root / "plan.yml"
            allowed.write_text("tasks: []", encoding="utf-8")
            outside = Path(directory) / "plan.yml"
            outside.write_text("tasks: []", encoding="utf-8")
            with patch.object(MODULE, "TEMP_PLAN_ROOT", root):
                MODULE.delete_temporary_plan(allowed)
                self.assertFalse(allowed.exists())
                with self.assertRaisesRegex(MODULE.PlanError, "아래의 계획 파일"):
                    MODULE.delete_temporary_plan(outside)
            self.assertTrue(outside.exists())

    def test_plan_verification_requires_every_jira_key_to_exist(self):
        plan = {"tasks": [{"jira_key": "TEST-1"}, {"jira_key": "TEST-2"}]}
        client = FakeClient()
        client.existing_keys.add("TEST-1")
        with self.assertRaisesRegex(MODULE.PlanError, "TEST-2"):
            MODULE.verify_plan_issues(plan, client)
        client.existing_keys.add("TEST-2")
        MODULE.verify_plan_issues(plan, client)

    def test_empty_success_response_is_supported(self):
        client = MODULE.JiraClient(
            MODULE.JiraConfig("https://example.atlassian.net", "TEST", "user", "token")
        )
        with patch.object(MODULE, "urlopen", return_value=EmptyResponse(None)):
            self.assertIsNone(client.request("/rest/api/3/issue/TEST-1", "PUT", {"fields": {}}))


if __name__ == "__main__":
    unittest.main()
