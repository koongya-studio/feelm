import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import stat
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('service', Path(__file__).with_name('ci-guard-service.py'))
service = importlib.util.module_from_spec(spec)
spec.loader.exec_module(service)


def payload():
    return {'nftables': [
        {'metainfo': {'version': '1.0.9'}},
        {'table': {'family': 'inet', 'name': 'feelm_ci_222', 'handle': 10, 'comment': 'marker'}},
        {'chain': {'family': 'inet', 'table': 'feelm_ci_222', 'name': 'output',
                   'handle': 1, 'type': 'filter', 'hook': 'output', 'prio': -10, 'policy': 'accept'}},
        {'rule': {'family': 'inet', 'table': 'feelm_ci_222', 'chain': 'output', 'handle': 2,
                  'expr': [{'match': {'left': {'meta': {'key': 'skuid'}}, 'op': '==', 'right': 1001}},
                           {'counter': {'packets': 50, 'bytes': 3000}}, {'reject': None}]}},
    ]}


class ServiceTests(unittest.TestCase):
    def test_canonical_ignores_only_handles_metadata_counter_values(self):
        first, second = payload(), payload()
        second['nftables'][0]['metainfo']['version'] = 'different'
        for entry in second['nftables'][1:]:
            next(iter(entry.values()))['handle'] = 999
        second['nftables'][3]['rule']['expr'][1]['counter']['packets'] = 100
        self.assertEqual(service.canonical(first), service.canonical(second))
        self.assertEqual(first, payload())

    def test_rule_modifications_are_detected(self):
        baseline = service.canonical(payload())
        changes = [
            lambda d: d['nftables'][1]['table'].update(comment='wrong'),
            lambda d: d['nftables'][1]['table'].update(flags=['dormant']),
            lambda d: d['nftables'][2]['chain'].update(prio=0),
            lambda d: d['nftables'][3]['rule']['expr'][0]['match'].update(right=1002),
            lambda d: d['nftables'][3]['rule']['expr'].append({'accept': None}),
            lambda d: d['nftables'][3]['rule']['expr'].reverse(),
        ]
        for change in changes:
            data = payload()
            change(data)
            with self.subTest(change=change):
                self.assertNotEqual(baseline, service.canonical(data))

    def test_rule_order_and_extra_rules_are_preserved(self):
        first = payload()
        extra = copy.deepcopy(first['nftables'][3])
        extra['rule']['expr'] = [{'accept': None}]
        first['nftables'].append(extra)
        second = copy.deepcopy(first)
        second['nftables'][3:] = reversed(second['nftables'][3:])
        self.assertNotEqual(service.canonical(first), service.canonical(second))
        self.assertNotEqual(service.canonical(first), service.canonical(payload()))

    def test_unknown_objects_duplicates_and_empty_tables_fail(self):
        for extra in ({'set': {}}, payload()['nftables'][1], payload()['nftables'][2]):
            data = payload()
            data['nftables'].append(extra)
            with self.assertRaises(ValueError):
                service.canonical(data)
        with self.assertRaises(ValueError):
            service.canonical({'nftables': []})

    def test_verify_mismatch_performs_no_mutation(self):
        with patch.object(service, 'execute', return_value=Mock(stdout=json.dumps(payload()))) as call:
            with self.assertRaisesRegex(ValueError, 'GUARD_MISMATCH'):
                service.verify({})
        call.assert_called_once_with(service.NFT, '-j', '-n', 'list', 'table', 'inet', 'feelm_ci_222')

    def test_ensure_existing_only_verifies(self):
        with patch.object(service, 'exists', return_value=True), patch.object(service, 'verify') as verify, \
                patch.object(service, 'execute') as execute:
            service.ensure('rules', {'expected': True})
        execute.assert_not_called()
        verify.assert_called_once_with({'expected': True})

    def test_ensure_absent_checks_creates_and_reads_back(self):
        with patch.object(service, 'exists', return_value=False), patch.object(service, 'verify') as verify, \
                patch.object(service, 'execute') as execute:
            service.ensure('create table inet feelm_ci_222', {})
        self.assertEqual(execute.call_count, 2)
        self.assertIn('--check', execute.call_args_list[0].args)
        self.assertEqual(execute.call_args_list[1].args, (service.NFT, '--file', '-'))
        verify.assert_called_once_with({})

    def test_failed_readback_does_not_delete(self):
        with patch.object(service, 'exists', return_value=False), \
                patch.object(service, 'verify', side_effect=ValueError('mismatch')), \
                patch.object(service, 'execute') as execute, self.assertRaises(ValueError):
            service.ensure('rules', {})
        self.assertEqual(execute.call_count, 2)
        self.assertFalse(any('delete' in call.args or 'flush' in call.args for call in execute.call_args_list))

    def test_reference_uses_isolated_network_and_checks_namespace_identity(self):
        with patch.object(service.os, 'readlink', return_value='net:[1]'), \
                patch.object(service, 'execute', return_value=Mock(stdout=json.dumps(payload()))) as execute:
            self.assertEqual(service.reference('rules'), service.canonical(payload()))
        self.assertEqual(execute.call_args.args[:4], ('/usr/bin/unshare', '--net', '--', '/usr/bin/python3'))
        self.assertEqual(execute.call_args.kwargs['input'], 'rules')
        compile(service.REFERENCE, 'reference', 'exec')
        self.assertIn("== sys.argv[1]", service.REFERENCE)

    def test_resource_limits_fail_closed(self):
        good = 'CPUQuotaPerSecUSec=2s\nMemoryHigh=5368709120\nMemoryMax=6442450944\nTasksMax=2048\n'
        with patch.object(service, 'execute', return_value=Mock(stdout=good)), \
                contextlib.redirect_stdout(io.StringIO()):
            service.resource_gate()
        for value in ('', good.replace('2s', 'infinity'), good.replace('2048', '4096')):
            with patch.object(service, 'execute', return_value=Mock(stdout=value)), self.assertRaises(ValueError):
                service.resource_gate()

    def test_config_rejects_examples_uid_change_and_injection_before_helper_import(self):
        for data in ({'uid': 1001, 'deny_ips': []}, {'uid': 1002, 'deny_ips': []},
                     {'uid': 1001, 'deny_ips': ['192.0.2.1', '192.0.2.2']},
                     {'uid': 1001, 'deny_ips': ['1.1.1.1; flush ruleset', '8.8.8.8']}):
            with patch.object(service, 'trusted_file', return_value=Mock(read_text=lambda: json.dumps(data))), \
                    self.assertRaises(ValueError):
                service.policy('/config')

    def test_trusted_paths_reject_user_owned_writable_and_symlinks(self):
        for uid, mode in ((1001, stat.S_IFREG | 0o644), (0, stat.S_IFREG | 0o666),
                          (0, stat.S_IFLNK | 0o777)):
            with patch.object(Path, 'lstat', return_value=SimpleNamespace(st_uid=uid, st_mode=mode)), \
                    self.assertRaises(ValueError):
                service.trusted_file('/example')

    def test_trusted_file_rejects_writable_parent(self):
        first = SimpleNamespace(st_uid=0, st_mode=stat.S_IFREG | 0o644)
        parent = SimpleNamespace(st_uid=1001, st_mode=stat.S_IFDIR | 0o755)
        with patch.object(Path, 'lstat', side_effect=[first, parent]), self.assertRaises(ValueError):
            service.trusted_file('/untrusted/file')

    def test_config_validates_account_uid_before_generating_policy(self):
        data = {'uid': 1001, 'deny_ips': ['1.1.1.1', '8.8.8.8']}
        for uid in (1001, 1002):
            guard = Mock()
            guard.account_uid.return_value = uid
            guard.rules.return_value = 'reviewed rules'
            spec = Mock()
            with patch.object(service, 'trusted_file', return_value=Mock(read_text=lambda: json.dumps(data))), \
                    patch.object(service.importlib.util, 'spec_from_file_location', return_value=spec), \
                    patch.object(service.importlib.util, 'module_from_spec', return_value=guard):
                if uid == 1001:
                    self.assertEqual(service.policy('/config'), (guard, 'reviewed rules'))
                    guard.rules.assert_called_once_with(1001, data['deny_ips'])
                else:
                    with self.assertRaises(ValueError):
                        service.policy('/config')
                    guard.rules.assert_not_called()

    def test_start_check_never_creates_missing_rules(self):
        with patch.object(service.sys, 'argv', ['guard', 'start-check']), \
                patch.object(service.os, 'geteuid', return_value=0, create=True), \
                patch.object(service, 'policy', return_value=(None, 'rules')), \
                patch.object(service, 'reference', return_value={}), \
                patch.object(service, 'verify', side_effect=ValueError('missing')), \
                patch.object(service, 'ensure') as ensure, patch.object(service, 'resource_gate') as gate, \
                self.assertRaises(ValueError):
            service.main()
        ensure.assert_not_called()
        gate.assert_not_called()

    def test_units_use_failure_not_condition_and_never_flush(self):
        base = Path(__file__).parent / 'systemd'
        unit = (base / 'feelm-ci-guard.service').read_text()
        dropin = (base / 'user-1001-guard.conf').read_text()
        directives = '\n'.join(line for line in (unit + dropin).splitlines() if not line.startswith('#'))
        self.assertNotIn('ExecStop=', directives)
        self.assertNotIn('ExecCondition=', directives)
        self.assertNotIn('flush', directives)
        self.assertIn('Requires=feelm-ci-guard.service', dropin)
        self.assertIn('ExecStartPre=+/usr/bin/python3 -I ', dropin)
        self.assertNotIn('gitlab-runner', directives)


if __name__ == '__main__':
    unittest.main()
