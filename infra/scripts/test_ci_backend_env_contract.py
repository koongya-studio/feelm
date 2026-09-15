import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("ci-backend-env-contract.py")
SPEC = importlib.util.spec_from_file_location("ci_backend_env_contract", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class BackendEnvironmentContractScriptTest(unittest.TestCase):
    def complete_environment(self):
        contract = MODULE.load_contract()
        environment = {
            name: "test-value" for name in contract.values() if not name.startswith("@")
        }
        environment.update({
            "CI_COMMIT_BRANCH": "main",
            "CI_COMMIT_REF_PROTECTED": "true",
            "CI_PIPELINE_SOURCE": "push",
        })
        return environment

    def test_repository_contract_matches_compose(self):
        MODULE.validate_compose(MODULE.load_contract(), MODULE.backend_environment())

    def test_rejects_literal_or_misplaced_reviewed_defaults(self):
        scenarios = (
            ({"REDIS_HOST": "@compose-default"}, {"REDIS_HOST": "literal"}),
            ({"MAIL_MODE": "@dev-default"}, {"MAIL_MODE": "smtp"}),
        )
        for contract, compose in scenarios:
            with self.subTest(contract=contract), self.assertRaises(MODULE.ContractError):
                MODULE.validate_compose(contract, compose)

    def test_invalid_contract_error_does_not_expose_line_contents(self):
        secret = "must-not-appear-in-output"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "contract.properties"
            path.write_text("JWT_SECRET=" + secret, encoding="utf-8")
            with self.assertRaises(MODULE.ContractError) as raised:
                MODULE.load_contract(path)
        self.assertIn("line 1", str(raised.exception))
        self.assertNotIn(secret, str(raised.exception))

    def test_accepts_complete_main_environment(self):
        environment = self.complete_environment()
        with patch.dict(os.environ, environment, clear=True), patch.object(
            MODULE.sys, "argv", [str(SCRIPT), "--require-values"]
        ):
            self.assertEqual(MODULE.main(), 0)

    def test_rejects_missing_or_empty_required_variable(self):
        contract = MODULE.load_contract()
        secret_value = "must-not-appear-in-output"
        required = sorted(name for name in contract.values() if not name.startswith("@"))
        for key in required:
            for missing_value in (None, ""):
                with self.subTest(key=key, missing_value=missing_value):
                    environment = self.complete_environment()
                    environment["JWT_SECRET"] = secret_value
                    if missing_value is None:
                        environment.pop(key)
                    else:
                        environment[key] = missing_value
                    with patch.dict(os.environ, environment, clear=True), patch.object(
                        MODULE.sys, "argv", [str(SCRIPT), "--require-values"]
                    ), patch("builtins.print") as output:
                        self.assertEqual(MODULE.main(), 1)
                    rendered = " ".join(str(call) for call in output.call_args_list)
                    self.assertIn(key, rendered)
                    self.assertNotIn(secret_value, rendered)

    def test_rejects_untrusted_pipeline_context(self):
        scenarios = (
            {"CI_COMMIT_BRANCH": "develop"},
            {"CI_COMMIT_REF_PROTECTED": "false"},
            {"CI_PIPELINE_SOURCE": "merge_request_event"},
            {"CI_PIPELINE_SOURCE": "api"},
            {"CI_PIPELINE_SOURCE": "web"},
        )
        for update in scenarios:
            with self.subTest(update=update):
                environment = self.complete_environment()
                environment.update(update)
                with patch.dict(os.environ, environment, clear=True), patch.object(
                    MODULE.sys, "argv", [str(SCRIPT), "--require-values"]
                ):
                    self.assertEqual(MODULE.main(), 2)


if __name__ == "__main__":
    unittest.main()
