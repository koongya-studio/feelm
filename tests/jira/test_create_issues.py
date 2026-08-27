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


if __name__ == "__main__":
    unittest.main()
