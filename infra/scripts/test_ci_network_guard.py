import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch, Mock
import contextlib
import io
import json

spec = importlib.util.spec_from_file_location("guard", Path(__file__).with_name("ci-network-guard.py"))
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class GuardTests(unittest.TestCase):
    def test_no_root(self):
        with self.assertRaises(ValueError):
            guard.rules(0, ["203.0.113.1"])

    def test_require_destinations(self):
        with self.assertRaises(ValueError):
            guard.rules(1001, [])

    def test_reject_injection(self):
        with self.assertRaises(ValueError):
            guard.rules(1001, ["1.2.3.4; flush ruleset"])

    def test_uid_and_scope(self):
        text = guard.rules(1001, ["203.0.113.1"])
        self.assertIn("meta skuid 1001 jump restricted", text)
        self.assertIn("policy accept", text)
        self.assertNotIn("flush", text)
        self.assertNotIn("established", text)
        self.assertIn("meta nfproto ipv6 counter reject", text)
        self.assertIn("169.254.0.0/16", text)
        self.assertIn("fib daddr type local", text)

    def test_deny_precedes_allow(self):
        text = guard.rules(1001, ["203.0.113.1", "203.0.113.1"])
        self.assertEqual(text.count("203.0.113.1"), 1)
        self.assertLess(text.index("203.0.113.1"), text.index("udp dport 53"))
        self.assertLess(text.index("169.254.0.0/16"), text.index("tcp dport { 80, 443 }"))

    def invoke(self, arguments, exists=False, ownership_error=False):
        with patch.object(guard.sys, "argv", ["guard"] + arguments), \
             patch.object(guard.os, "geteuid", return_value=0, create=True), \
             patch.object(guard, "account_uid", return_value=1001), \
             patch.object(guard, "existing_table", return_value=exists), \
             patch.object(guard, "owned_table", side_effect=ValueError("not ours") if ownership_error else None), \
             patch.object(guard, "run", return_value=Mock(stdout="")) as run, \
             contextlib.redirect_stdout(io.StringIO()) as output, contextlib.redirect_stderr(io.StringIO()):
            try:
                guard.main()
            finally:
                self.calls = run.call_args_list
                self.output = output.getvalue()

    def test_vpc_dns_only_allows_exact_address_and_ports(self):
        text = guard.rules(1001, ["203.0.113.1"])
        resolver_lines = [line.strip() for line in text.splitlines() if "172.26.0.2" in line]
        self.assertEqual(resolver_lines, [
            "ip daddr 172.26.0.2 udp dport 53 counter return",
            "ip daddr 172.26.0.2 tcp dport 53 counter return",
        ])
        self.assertIn("meta skuid 1001 jump restricted", text)
        self.assertNotIn("hook input", text)

    def test_dns_exception_precedes_private_deny_not_explicit_deny(self):
        text = guard.rules(1001, ["172.26.0.2", "203.0.113.1"])
        for protocol in ("udp", "tcp"):
            dns = f"ip daddr 172.26.0.2 {protocol} dport 53 counter return"
            self.assertLess(text.index("ip daddr 172.26.0.2 counter reject"), text.index(dns))
            self.assertLess(text.index("ip daddr 203.0.113.1 counter reject"), text.index(dns))
            self.assertLess(text.index(dns), text.index("ip daddr { 0.0.0.0/8"))

    def test_dns_change_preserves_other_allow_rules_and_denials(self):
        text = guard.rules(1001, ["203.0.113.1"])
        allows = [line.strip() for line in text.splitlines() if "counter return" in line]
        self.assertEqual(allows, [
            "ip daddr 127.0.0.53 udp dport 53 counter return",
            "ip daddr 127.0.0.53 tcp dport 53 counter return",
            "ip daddr 172.26.0.2 udp dport 53 counter return",
            "ip daddr 172.26.0.2 tcp dport 53 counter return",
            "tcp dport { 80, 443 } counter return",
        ])
        self.assertIn("meta nfproto ipv6 counter reject", text)
        self.assertIn("169.254.0.0/16, 172.16.0.0/12", text)
        self.assertIn("fib daddr type local counter reject", text)
        self.assertTrue(text.endswith("  counter reject\n }\n}\n"))

    def test_default_is_plan_without_commands(self):
        self.invoke(["--deny-ip", "203.0.113.1"])
        self.assertEqual(self.calls, [])

    def test_check_only_uses_dry_run(self):
        self.invoke(["check", "--deny-ip", "203.0.113.1"])
        self.assertEqual(len(self.calls), 1)
        self.assertIn("--check", self.calls[0].args)

    def test_apply_needs_confirmation(self):
        with self.assertRaises(SystemExit):
            self.invoke(["apply", "--deny-ip", "203.0.113.1"])
        self.assertTrue(all("--check" in call.args for call in self.calls))

    def test_apply_checks_then_installs_once(self):
        self.invoke(["apply", "--confirm", "--deny-ip", "203.0.113.1"])
        self.assertEqual(len(self.calls), 2)
        self.assertIn("--check", self.calls[0].args)
        self.assertEqual(self.calls[1].args, ("nft", "--file", "-"))
        self.assertEqual(self.calls[0].kwargs, self.calls[1].kwargs)
        self.assertEqual(self.calls[1].kwargs["input"].splitlines()[0],
                         f'create table inet feelm_ci_222 {{ comment "{guard.MARKER}"; }}')

    def test_apply_reports_failed_readback_without_deleting_rules(self):
        with self.assertRaisesRegex(ValueError, "POST_APPLY_VERIFICATION_FAILED"):
            self.invoke(["apply", "--confirm", "--deny-ip", "203.0.113.1"], ownership_error=True)
        self.assertEqual(len(self.calls), 2)
        self.assertNotIn("TEMPORARY_GUARD_APPLIED", self.output)
        self.assertFalse(any("delete" in call.args for call in self.calls))

    def test_real_ownership_check_accepts_marker(self):
        payload = {"nftables": [{"table": {"comment": guard.MARKER}}]}
        with patch.object(guard, "run", return_value=Mock(stdout=json.dumps(payload))):
            guard.owned_table()

    def test_real_ownership_check_rejects_missing_marker(self):
        payload = {"nftables": [{"table": {"name": guard.TABLE}}]}
        with patch.object(guard, "run", return_value=Mock(stdout=json.dumps(payload))):
            with self.assertRaisesRegex(ValueError, "ownership mismatch"):
                guard.owned_table()

    def test_existing_table_is_not_replaced(self):
        with self.assertRaises(ValueError):
            self.invoke(["apply", "--confirm", "--deny-ip", "203.0.113.1"], exists=True)
        self.assertEqual(self.calls, [])

    def test_rollback_only_deletes_owned_table(self):
        self.invoke(["rollback", "--confirm"], exists=True)
        self.assertEqual(self.calls[0].args, ("nft", "delete", "table", "inet", "feelm_ci_222"))
        self.assertEqual(len(self.calls), 1)

    def test_rollback_refuses_foreign_table(self):
        with self.assertRaises(ValueError):
            self.invoke(["rollback", "--confirm"], exists=True, ownership_error=True)
        self.assertEqual(self.calls, [])

    def test_rollback_needs_confirmation(self):
        with self.assertRaises(SystemExit):
            self.invoke(["rollback"], exists=True)
        self.assertEqual(self.calls, [])


if __name__ == "__main__":
    unittest.main()
