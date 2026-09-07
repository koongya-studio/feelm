import copy
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('review_guard', Path(__file__).with_name('network-guard.py'))
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class ReviewGuardTests(unittest.TestCase):
    def setUp(self):
        # 테스트용 공개 주소이며 실제 네트워크 연결은 수행하지 않는다.
        self.config = {'uid': 997, 'deny_ips': ['8.8.8.8', '1.1.1.1'],
                       'dns_ips': ['127.0.0.53', '172.26.0.2']}

    def test_isolates_only_review_account(self):
        source = guard.render(self.config)
        self.assertIn('meta skuid 997 jump restricted', source)
        self.assertNotIn('feelm_ci_222', source)
        self.assertNotIn('flush', source)
        self.assertNotIn('delete', source)

    def test_deny_precedes_https_and_no_established_bypass(self):
        source = guard.render(self.config)
        self.assertLess(source.index('ip daddr 8.8.8.8 counter reject'),
                        source.index('tcp dport 443 counter return'))
        self.assertLess(source.index('fib daddr type local counter reject'),
                        source.index('tcp dport 443 counter return'))
        self.assertNotIn('established', source)
        self.assertNotIn('dport 80', source)
        self.assertIn('meta nfproto ipv6 counter reject', source)
        self.assertIn('169.254.0.0/16', source)

    def test_dns_exception_is_port_limited(self):
        source = guard.render(self.config)
        for address in self.config['dns_ips']:
            for protocol in ['tcp', 'udp']:
                self.assertIn(f'ip daddr {address} {protocol} dport 53 counter return', source)
            self.assertNotIn(f'ip daddr {address} counter return', source)

    def test_rejects_invalid_uid(self):
        for uid in [0, -1, True, '997', 4294967295]:
            with self.subTest(uid=uid), self.assertRaises(ValueError):
                guard.render(dict(self.config, uid=uid))

    def test_rejects_missing_duplicate_private_or_injected_deny_addresses(self):
        for denied in [[], ['8.8.8.8'], ['8.8.8.8'] * 2,
                       ['8.8.8.8', '10.0.0.1'], ['8.8.8.8', '1.1.1.1; flush ruleset']]:
            with self.subTest(denied=denied), self.assertRaises(ValueError):
                guard.render(dict(self.config, deny_ips=denied))

    def test_rejects_invalid_dns(self):
        for dns in [[], ['::1'], ['169.254.169.254'], ['0.0.0.0'], ['224.0.0.1'],
                    ['8.8.8.8'], ['127.0.0.53'] * 2]:
            with self.subTest(dns=dns), self.assertRaises(ValueError):
                guard.render(dict(self.config, dns_ips=dns))

    def test_refuses_to_replace_existing_table_on_mismatch(self):
        with patch.object(guard, 'exists', return_value=True), \
                patch.object(guard, 'verify', side_effect=ValueError('mismatch')), \
                patch.object(guard, 'execute') as execute:
            with self.assertRaises(ValueError):
                guard.ensure('rules', {})
            execute.assert_not_called()

    def test_failed_readback_never_removes_new_rules(self):
        with patch.object(guard, 'exists', return_value=False), \
                patch.object(guard, 'verify', side_effect=ValueError('mismatch')), \
                patch.object(guard, 'execute') as execute:
            with self.assertRaises(ValueError):
                guard.ensure('rules', {})
            self.assertEqual(execute.call_count, 2)
            self.assertFalse(any('delete' in call.args for call in execute.call_args_list))

    def test_canonical_ignores_counters_but_not_rule_changes(self):
        payload = {'nftables': [
            {'table': {'family': 'inet', 'name': guard.TABLE, 'handle': 1}},
            {'chain': {'family': 'inet', 'table': guard.TABLE, 'name': 'restricted'}},
            {'rule': {'family': 'inet', 'table': guard.TABLE, 'chain': 'restricted',
                      'expr': [{'counter': {'packets': 0, 'bytes': 0}}, {'reject': None}]}}]}
        other = copy.deepcopy(payload)
        other['nftables'][2]['rule']['expr'][0]['counter']['packets'] = 10
        self.assertEqual(guard.canonical(payload), guard.canonical(other))
        other['nftables'][2]['rule']['expr'][1] = {'accept': None}
        self.assertNotEqual(guard.canonical(payload), guard.canonical(other))


if __name__ == '__main__':
    unittest.main()
