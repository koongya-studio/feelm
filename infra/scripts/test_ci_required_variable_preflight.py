import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit


SCRIPT = Path(__file__).with_name("ci-required-variable-preflight.py")
SPEC = importlib.util.spec_from_file_location("ci_required_variable_preflight", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FakeResponse:
    def __init__(self, payload):
        self.payload = json.dumps(payload).encode()

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self):
        return self.payload


class RequiredVariablePreflightTest(unittest.TestCase):
    secret = "must-not-appear-in-output"

    def variables(self):
        return MODULE.scoped_variables(MODULE.load_contract(), "service")

    def environment(self):
        environment = {
            "CI_COMMIT_BRANCH": "main",
            "CI_COMMIT_REF_PROTECTED": "true",
            "CI_PIPELINE_SOURCE": "push",
            "CI_ENVIRONMENT_NAME": "service",
            "CI_API_V4_URL": "https://gitlab.example/api/v4",
            "CI_PROJECT_ID": "123",
            "CI_VARIABLE_AUDIT_TOKEN": self.secret,
            "DISTRIBUTED_NODE_IP": "10.0.0.12",
            "GRAFANA_ADMIN_PASSWORD": self.secret,
            "GRAFANA_ALERT_HMAC_SECRET": self.secret,
            "GOOGLE_CLIENT_ID": "google-client-id",
            "GOOGLE_CLIENT_SECRET": self.secret,
            "KAKAO_CLIENT_ID": "kakao-client-id",
            "KAKAO_CLIENT_SECRET": self.secret,
            "NAVER_CLIENT_ID": "naver-client-id",
            "NAVER_CLIENT_SECRET": self.secret,
            "OAUTH_ALLOWED_REDIRECT_URIS": "https://service.example/app/oauth/callback",
            "POSTGRES_DB": "feelm",
            "POSTGRES_PASSWORD": self.secret,
            "POSTGRES_USER": "feelm",
            "REDIS_PASSWORD": self.secret,
        }
        return environment

    def opener(self, request, timeout):
        self.assertEqual(timeout, 5)
        self.assertEqual(request.headers["Private-token"], self.secret)
        parsed = urlsplit(request.full_url)
        key = parsed.path.rsplit("/", 1)[-1]
        self.assertEqual(parse_qs(parsed.query)["filter[environment_scope]"], ["service"])
        expected = next(entry for entry in self.variables() if entry["key"] == key)
        return FakeResponse({**expected, "value": self.secret})

    def test_repository_contract_is_value_free_and_valid(self):
        variables = MODULE.load_contract()
        self.assertEqual(len(variables), 14)
        self.assertNotIn(self.secret, json.dumps(variables))
        self.assertTrue(all("value" not in entry for entry in variables))

        by_key = {entry["key"]: entry for entry in variables}
        for key in ("GOOGLE_CLIENT_SECRET", "KAKAO_CLIENT_SECRET", "NAVER_CLIENT_SECRET"):
            self.assertTrue(by_key[key]["masked"])
            self.assertTrue(by_key[key]["hidden"])
        for key in (
            "GOOGLE_CLIENT_ID",
            "KAKAO_CLIENT_ID",
            "NAVER_CLIENT_ID",
            "OAUTH_ALLOWED_REDIRECT_URIS",
        ):
            self.assertFalse(by_key[key]["masked"])
            self.assertFalse(by_key[key]["hidden"])

    def test_contract_rejects_value_field_and_duplicate(self):
        entry = dict(self.variables()[0])
        scenarios = (
            {"schema_version": 1, "variables": [{**entry, "value": self.secret}]},
            {"schema_version": 1, "variables": [entry, entry]},
        )
        for payload in scenarios:
            with self.subTest(payload=payload), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "contract.json"
                path.write_text(json.dumps(payload), encoding="utf-8")
                with self.assertRaises(MODULE.PreflightError):
                    MODULE.load_contract(path)

    def test_runtime_values_pass_without_being_returned(self):
        MODULE.validate_injected_values(self.variables(), self.environment())

    def test_missing_and_invalid_values_report_only_keys(self):
        for key, value in (
            ("GRAFANA_ADMIN_PASSWORD", ""),
            ("GRAFANA_ALERT_HMAC_SECRET", ""),
            ("DISTRIBUTED_NODE_IP", "203.0.113.8"),
            ("POSTGRES_DB", "not valid"),
        ):
            with self.subTest(key=key):
                environment = self.environment()
                environment[key] = value
                environment["REDIS_PASSWORD"] = self.secret
                with self.assertRaises(MODULE.PreflightError) as raised:
                    MODULE.validate_injected_values(self.variables(), environment)
                self.assertIn(key, str(raised.exception))
                self.assertNotIn(self.secret, str(raised.exception))

    def test_rejects_untrusted_or_debug_context(self):
        scenarios = (
            {"CI_COMMIT_BRANCH": "develop"},
            {"CI_COMMIT_BRANCH": "feature/example"},
            {"CI_PIPELINE_SOURCE": "web"},
            {"CI_COMMIT_REF_PROTECTED": "false"},
            {"CI_PIPELINE_SOURCE": "merge_request_event"},
            {"CI_ENVIRONMENT_NAME": "distributed"},
            {"CI_DEBUG_TRACE": "true"},
        )
        for update in scenarios:
            with self.subTest(update=update):
                environment = self.environment()
                environment.update(update)
                with self.assertRaises(MODULE.PreflightError):
                    MODULE.require_protected_main(environment)

    def test_live_metadata_matches_without_exposing_values(self):
        MODULE.validate_live_metadata(self.variables(), self.environment(), self.opener)

    def test_metadata_mismatch_names_only_fields(self):
        def opener(request, timeout):
            response = self.opener(request, timeout)
            payload = json.loads(response.payload)
            payload["masked"] = not payload["masked"]
            return FakeResponse(payload)

        with self.assertRaises(MODULE.PreflightError) as raised:
            MODULE.validate_live_metadata(self.variables(), self.environment(), opener)
        rendered = str(raised.exception)
        self.assertIn("masked", rendered)
        self.assertNotIn(self.secret, rendered)

    def test_main_static_check_does_not_require_runtime_secrets(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(
            MODULE.sys, "argv", [str(SCRIPT)]
        ):
            self.assertEqual(MODULE.main(), 0)

    def test_main_runtime_check_fails_closed_without_printing_secret(self):
        environment = self.environment()
        environment.pop("REDIS_PASSWORD")
        with patch.dict(os.environ, environment, clear=True), patch.object(
            MODULE.sys,
            "argv",
            [str(SCRIPT), "--require-values", "--check-metadata"],
        ), patch("builtins.print") as output:
            self.assertEqual(MODULE.main(), 1)
        rendered = " ".join(str(call) for call in output.call_args_list)
        self.assertIn("REDIS_PASSWORD", rendered)
        self.assertNotIn(self.secret, rendered)

    def test_main_runtime_values_accept_protected_main_push(self):
        with patch.dict(os.environ, self.environment(), clear=True), patch.object(
            MODULE.sys, "argv", [str(SCRIPT), "--require-values"]
        ), patch("builtins.print") as output:
            self.assertEqual(MODULE.main(), 0)
        rendered = " ".join(str(call) for call in output.call_args_list)
        self.assertNotIn(self.secret, rendered)


if __name__ == "__main__":
    unittest.main()
