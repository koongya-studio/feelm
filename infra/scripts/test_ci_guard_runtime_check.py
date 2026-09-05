import contextlib
import importlib.util
import io
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('runtime_check', Path(__file__).with_name('ci-guard-runtime-check.py'))
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class RuntimeTests(unittest.TestCase):
    def test_plan_has_no_execution(self):
        with patch.object(runtime.sys, 'argv', ['runtime']), patch.object(runtime, 'run') as run, \
                contextlib.redirect_stdout(io.StringIO()):
            runtime.main()
        run.assert_not_called()

    def test_run_requires_confirmation(self):
        with patch.object(runtime.sys, 'argv', ['runtime', 'run']), patch.object(runtime, 'run') as run, \
                contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            runtime.main()
        run.assert_not_called()

    def test_service_limits_and_watchdog_are_not_optional(self):
        args = runtime.command('fixture', Path('/run/fixture'), 'missing', 'net:[1]')
        for required in ('--property=PrivateNetwork=yes', '--property=NotifyAccess=main',
                         '--property=KillMode=control-group', '--property=Restart=no',
                         '--property=WatchdogSec=3', '--property=WatchdogSignal=SIGKILL',
                         '--property=RuntimeMaxSec=20', '--property=LimitCORE=0'):
            self.assertIn(required, args)
        self.assertFalse(any('user@' in arg or 'docker.sock' in arg for arg in args))

    def test_all_expected_outcomes_require_stopped_child(self):
        outcomes = ('completed', 'missing_detected', 'mismatch_detected', 'heartbeat_stopped')
        for case, outcome in zip(runtime.CASES, outcomes):
            state = {'ActiveState': 'inactive' if case == 'valid' else 'failed', 'MainPID': '0',
                     'Result': 'success' if case == 'valid' else 'watchdog' if case == 'watchdog' else 'exit-code'}
            evidence = {'outcome': outcome, 'netns': 'net:[2]', 'initial_verified': True, 'offline': True}
            runtime.evaluate(case, state, evidence, 'net:[1]', False)
            for changed in (dict(state, Result='timeout'), dict(state, MainPID='42')):
                with self.assertRaises(ValueError):
                    runtime.evaluate(case, changed, evidence, 'net:[1]', False)
            with self.assertRaises(ValueError):
                runtime.evaluate(case, state, evidence, 'net:[1]', True)
            for changed in (dict(evidence, netns='net:[1]'), dict(evidence, initial_verified=False),
                            dict(evidence, offline=False), dict(evidence, outcome='started')):
                with self.assertRaises(ValueError):
                    runtime.evaluate(case, state, changed, 'net:[1]', False)

    def test_pid_reuse_and_zombie_not_reported_as_running(self):
        for status, stamp, expected in [('S', '99', True), ('Z', '99', False), ('S', '100', False)]:
            text = '12 (sleep) ' + ' '.join([status] + ['0'] * 18 + [stamp])
            with patch.object(Path, 'read_text', return_value=text):
                self.assertEqual(runtime.same_process(12, '99'), expected)
        with patch.object(Path, 'read_text', side_effect=FileNotFoundError):
            self.assertFalse(runtime.same_process(12, '99'))

    def test_fixture_guards_before_mutation_and_never_uses_operating_socket(self):
        code = runtime.SUPERVISOR
        compile(code, 'supervisor', 'exec')
        self.assertLess(code.index('offline()  #'), code.index('service.execute('))
        self.assertIn('start_new_session=True', code)
        self.assertIn('service.verify(expected)', code)
        self.assertNotIn('docker.sock', code)
        self.assertNotIn('flush', code)
        self.assertNotIn('service.ensure(', code)

    def test_cleanup_accepts_already_collected_unit(self):
        with patch.object(runtime.support, 'execute', return_value=SimpleNamespace(
                stdout='LoadState=not-found\nActiveState=inactive\nMainPID=0\n')) as execute:
            runtime.cleanup_unit('fixture.service')
        self.assertEqual(execute.call_count, 1)

    def test_cleanup_does_not_hide_running_unit(self):
        with patch.object(runtime.support, 'execute', return_value=SimpleNamespace(
                stdout='LoadState=loaded\nActiveState=active\nMainPID=12\n')), self.assertRaises(ValueError):
            runtime.cleanup_unit('fixture.service')


if __name__ == '__main__':
    unittest.main()
