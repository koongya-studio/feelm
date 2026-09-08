import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import Mock


spec = importlib.util.spec_from_file_location("comment_trigger", Path(__file__).with_name("comment-trigger.py"))
trigger = importlib.util.module_from_spec(spec)
spec.loader.exec_module(trigger)


def event(note="/claude-review", project_id=123, user_id=9, iid=7):
    return {"object_kind": "note", "project": {"id": project_id}, "user": {"id": user_id},
            "object_attributes": {"noteable_type": "MergeRequest", "note": note, "action": "create"},
            "merge_request": {"iid": iid}}


class CommentTriggerTests(unittest.TestCase):
    def test_comment_pipeline_runs_only_review_job(self):
        ci = (Path(__file__).parents[2] / ".gitlab-ci.yml").read_text(encoding="utf-8")
        marker = '$CI_PIPELINE_SOURCE == "api" && $CLAUDE_REVIEW_TRIGGER == "comment"'
        self.assertEqual(ci.count(marker), 5)
        self.assertIn("when: on_success", ci[ci.index("claude-mr-review:"):ci.index("runner-check:")])
        for job in ("runner-check:", "backend-test:", "backend-image:", "deploy-backend:"):
            section = ci[ci.index(job):]
            self.assertLess(section.index("when: never"), section.index("$CI_COMMIT_BRANCH"))

    def test_accepts_only_exact_command_for_configured_project(self):
        self.assertEqual(trigger.parse_note_event(event(), 123), (7, 9))
        for payload, code in [(event(note="please /claude-review"), "IGNORED_EVENT"),
                              (event(project_id=456), "PROJECT_MISMATCH"),
                              ({"object_kind": "push"}, "IGNORED_EVENT")]:
            with self.subTest(code=code), self.assertRaises(trigger.TriggerError) as raised:
                trigger.parse_note_event(payload, 123)
            self.assertEqual(raised.exception.code, code)

        with self.assertRaises(trigger.TriggerError) as raised:
            trigger.parse_note_event(event(note={"command": "/claude-review"}), 123)
        self.assertEqual(raised.exception.code, "IGNORED_EVENT")

    def test_verifies_member_and_current_mr_before_protected_pipeline(self):
        responses = [
            {"access_level": 30},
            {"iid": 7, "state": "opened", "target_branch": "develop",
             "source_project_id": 123, "target_project_id": 123, "sha": "a" * 40},
            {"id": 88},
        ]
        client = trigger.GitLabClient("https://gitlab.example/api/v4", 123, "secret")
        client.request = Mock(side_effect=responses)
        self.assertEqual(client.verify_and_trigger(7, 9), 88)
        method, path, payload = client.request.call_args_list[-1].args
        self.assertEqual((method, path), ("POST", "/projects/123/pipeline"))
        self.assertEqual(payload["ref"], "develop")
        self.assertIn({"key": "REVIEW_MR_SHA", "value": "a" * 40}, payload["variables"])

    def test_rejects_insufficient_member_and_unreviewable_mr(self):
        client = trigger.GitLabClient("https://gitlab.example/api/v4", 123, "secret")
        client.request = Mock(return_value={"access_level": 20})
        with self.assertRaises(trigger.TriggerError) as raised:
            client.verify_and_trigger(7, 9)
        self.assertEqual(raised.exception.code, "INSUFFICIENT_ACCESS")

        client.request = Mock(side_effect=[{"access_level": 30}, {"iid": 7, "state": "merged"}])
        with self.assertRaises(trigger.TriggerError) as raised:
            client.verify_and_trigger(7, 9)
        self.assertEqual(raised.exception.code, "MR_NOT_REVIEWABLE")

    def test_authenticates_webhook_and_rate_limits_successful_request(self):
        client = Mock()
        client.verify_and_trigger.return_value = 88
        clock = Mock(return_value=1000)
        app = trigger.TriggerApplication(123, "hook-secret", client, clock=clock)
        body = json.dumps(event()).encode()
        self.assertEqual(app.handle("hook-secret", "Note Hook", body), (202, "PIPELINE_TRIGGERED:88"))
        with self.assertRaises(trigger.TriggerError) as raised:
            app.handle("hook-secret", "Note Hook", body)
        self.assertEqual(raised.exception.code, "REQUEST_COOLDOWN")
        with self.assertRaises(trigger.TriggerError) as raised:
            app.handle("wrong", "Note Hook", body)
        self.assertEqual(raised.exception.code, "INVALID_WEBHOOK_TOKEN")

    def test_failed_api_call_does_not_consume_cooldown(self):
        client = Mock()
        client.verify_and_trigger.side_effect = trigger.TriggerError("GITLAB_API_FAILED", 502)
        app = trigger.TriggerApplication(123, "hook-secret", client, clock=lambda: 1000)
        for _ in range(2):
            with self.assertRaises(trigger.TriggerError):
                app.handle("hook-secret", "Note Hook", json.dumps(event()).encode())
        self.assertEqual(client.verify_and_trigger.call_count, 2)


if __name__ == "__main__":
    unittest.main()
