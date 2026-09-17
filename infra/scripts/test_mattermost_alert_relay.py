import hashlib
import hmac
import importlib.util
import json
import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).parents[1] / "monitoring/mattermost-relay/relay.py"
SPEC = importlib.util.spec_from_file_location("mattermost_alert_relay", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class MattermostAlertRelayTest(unittest.TestCase):
    def setUp(self):
        self.environ = {
            "MATTERMOST_BASE_URL": "https://mattermost.example",
            "MATTERMOST_LOGIN_ID": "alert-user",
            "MATTERMOST_PASSWORD": "password",
            "MATTERMOST_CHANNEL_ID": "channel-id",
            "GRAFANA_ALERT_HMAC_SECRET": "signing-secret",
        }

    def test_signature_includes_timestamp_and_raw_body(self):
        body = b'{"status":"firing"}'
        timestamp = "1700000000"
        signature = hmac.new(
            self.environ["GRAFANA_ALERT_HMAC_SECRET"].encode(),
            timestamp.encode() + b":" + body,
            hashlib.sha256,
        ).hexdigest()
        with patch.dict(os.environ, self.environ, clear=True):
            MODULE.verify_signature(body, signature, timestamp, now=int(timestamp))
            with self.assertRaisesRegex(MODULE.RelayError, "signature"):
                MODULE.verify_signature(body + b" ", signature, timestamp, now=int(timestamp))

    def test_expired_timestamp_is_rejected(self):
        with patch.dict(os.environ, self.environ, clear=True):
            with self.assertRaisesRegex(MODULE.RelayError, "expired"):
                MODULE.verify_signature(b"{}", "ignored", "100", now=1000)

    def test_posts_with_login_channel_and_logout(self):
        responses = [
            ({"id": "user"}, {"Token": "session-token"}),
            ({"id": "post"}, {}),
            (None, {}),
        ]
        with patch.dict(os.environ, self.environ, clear=True), patch.object(
            MODULE, "api_request", side_effect=responses
        ) as request:
            MODULE.post_to_mattermost("message")

        self.assertTrue(request.call_args_list[0].args[0].endswith("/api/v4/users/login"))
        post = request.call_args_list[1]
        self.assertTrue(post.args[0].endswith("/api/v4/posts"))
        self.assertEqual(post.kwargs["payload"], {"channel_id": "channel-id", "message": "message"})
        self.assertTrue(request.call_args_list[2].args[0].endswith("/api/v4/users/logout"))

    def test_builds_firing_and_recovery_messages(self):
        firing = MODULE.build_message({"status": "firing", "title": "Alert", "message": "Detail"})
        self.assertEqual(firing, "## Alert\n\nDetail")
        recovery = MODULE.build_message({"status": "resolved"})
        self.assertIn("RECOVERY", recovery)


if __name__ == "__main__":
    unittest.main()
