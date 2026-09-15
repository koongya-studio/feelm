import contextlib
import importlib.util
import io
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('failure_check', Path(__file__).with_name('ci-guard-failure-check.py'))
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


class FailureCheckTests(unittest.TestCase):
    def test_plan_never_runs_system_commands(self):
        with patch.object(check.sys, 'argv', ['check']), patch.object(check, 'run') as run, \
                contextlib.redirect_stdout(io.StringIO()):
            check.main()
        run.assert_not_called()

    def test_run_requires_confirmation(self):
        with patch.object(check.sys, 'argv', ['check', 'run']), patch.object(check, 'run') as run, \
                contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            check.main()
        run.assert_not_called()

    def test_command_is_private_network_with_precheck_and_no_production_dependency(self):
        command = check.command('ci222-failcheck-example.service', Path('/run/example'), 'missing', 'net:[1]')
        self.assertIn('--property=PrivateNetwork=yes', command)
        self.assertIn('--service-type=oneshot', command)
        self.assertTrue(any(x.startswith('--property=ExecStartPre=/usr/bin/python3 -I -B ') for x in command))
        self.assertFalse(any('ExecCondition' in x or 'user@1001' in x or 'Requires=' in x for x in command))

    def test_valid_requires_workload_success(self):
        state = {'ActiveState': 'active', 'Result': 'success'}
        evidence = {'outcome': 'verified', 'netns': 'net:[2]'}
        check.evaluate('valid', 0, state, evidence, True, 'net:[1]')
        for result, started in ((1, True), (0, False)):
            with self.assertRaises(ValueError):
                check.evaluate('valid', result, state, evidence, started, 'net:[1]')

    def test_negative_requires_expected_gate_reason_and_no_main_execution(self):
        state = {'ActiveState': 'failed', 'Result': 'exit-code',
                 'ExecMainStartTimestampMonotonic': '0', 'ExecStartPre': '{ status=1 ; }'}
        for case, outcome in [('missing', 'missing'), ('modified', 'mismatch')]:
            evidence = {'outcome': outcome, 'netns': 'net:[2]'}
            check.evaluate(case, 1, state, evidence, False, 'net:[1]')
            for change in ({'Result': 'timeout'}, {'ExecMainStartTimestampMonotonic': '10'},
                           {'ExecStartPre': '{ status=10 ; }'}, {'ActiveState': 'inactive'}):
                with self.assertRaises(ValueError):
                    check.evaluate(case, 1, dict(state, **change), evidence, False, 'net:[1]')
            with self.assertRaises(ValueError):
                check.evaluate(case, 1, state, evidence, True, 'net:[1]')
            with self.assertRaises(ValueError):
                check.evaluate(case, 0, state, evidence, False, 'net:[1]')

    def test_missing_evidence_or_host_namespace_never_passes(self):
        state = {'ActiveState': 'failed', 'Result': 'exit-code',
                 'ExecMainStartTimestampMonotonic': '0', 'ExecStartPre': '{ status=1 ; }'}
        for evidence in ({}, {'outcome': 'missing', 'netns': 'net:[1]'},
                         {'outcome': 'mismatch', 'netns': 'net:[2]'}):
            with self.assertRaises(ValueError):
                check.evaluate('missing', 1, state, evidence, False, 'net:[1]')

    def test_embedded_programs_compile_and_guard_before_mutation(self):
        compile(check.GATE, 'gate', 'exec')
        compile(check.WORKLOAD, 'workload', 'exec')
        self.assertLess(check.GATE.index('Refusing fixture'), check.GATE.index('service.execute('))
        self.assertIn('service.verify(expected)', check.GATE)
        self.assertNotIn('service.ensure(', check.GATE)
        self.assertNotIn('flush', check.GATE)


if __name__ == '__main__':
    unittest.main()
