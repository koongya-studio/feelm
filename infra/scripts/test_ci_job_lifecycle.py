import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('lifecycle', Path(__file__).with_name('ci-job-lifecycle.py'))
life = importlib.util.module_from_spec(spec)
import sys
sys.modules[spec.name] = life
spec.loader.exec_module(life)


class Driver:
    def __init__(self, fail=None, code=0, report=None):
        self.calls = []; self.fail = fail; self.code = code
        self.report = {'passed': True} if report is None else report
    def call(self, name):
        self.calls.append(name)
        if self.fail == name: raise RuntimeError('synthetic failure, not report data')
    def prepare(self, deadline): self.call('prepare')
    def start(self, deadline): self.call('start')
    def poll(self, deadline): self.call('poll'); return self.code
    def stop(self, deadline): self.call('stop')
    def verify_stopped(self, deadline): self.call('verify_stopped')
    def collect(self, code, deadline): self.call('collect'); return self.report


class LifecycleTests(unittest.TestCase):
    def test_normal_stops_before_collection(self):
        driver = Driver(); result = life.supervise(driver, life.Attempt(), 60)
        self.assertTrue(result['passed'])
        self.assertEqual(driver.calls, ['prepare', 'start', 'poll', 'stop', 'verify_stopped', 'collect'])

    def test_all_execution_failures_still_stop(self):
        for fail in ('prepare', 'start', 'poll'):
            driver = Driver(fail=fail)
            result = life.supervise(driver, life.Attempt(), 60)
            self.assertEqual(result['outcome'], 'infrastructure_failure')
            self.assertEqual(driver.calls[-2:], ['stop', 'verify_stopped'])
            self.assertNotIn('collect', driver.calls)

    def test_cleanup_failures_quarantine_without_collecting(self):
        for fail in ('stop', 'verify_stopped'):
            driver = Driver(fail=fail)
            result = life.supervise(driver, life.Attempt(), 60)
            self.assertEqual(result['phase'], 'quarantined')
            self.assertFalse(result['passed'])
            self.assertNotIn('collect', driver.calls)

    def test_test_exit_cannot_be_overridden_by_report(self):
        result = life.supervise(Driver(code=1), life.Attempt(), 60)
        self.assertEqual(result['outcome'], 'test_failure')
        self.assertFalse(result['passed'])

    def test_report_failure_cannot_pass(self):
        for report in ({'passed': False}, {'passed': 'true'}, {}):
            result = life.supervise(Driver(report=report), life.Attempt(), 60)
            self.assertFalse(result['passed'])
            self.assertEqual(result['outcome'], 'report_failure')

    def test_collector_exception_quarantines(self):
        result = life.supervise(Driver(fail='collect'), life.Attempt(), 60)
        self.assertEqual(result['phase'], 'quarantined')

    def test_cancel_before_start_still_checks_cleanup(self):
        driver = Driver()
        result = life.supervise(driver, life.Attempt(), 60, cancelled=lambda: True)
        self.assertEqual(result['outcome'], 'cancelled')
        self.assertEqual(driver.calls, ['stop', 'verify_stopped'])

    def test_cancel_during_poll(self):
        driver = Driver(code=None)
        result = life.supervise(driver, life.Attempt(), 60, cancelled=lambda: 'poll' in driver.calls)
        self.assertEqual(result['outcome'], 'cancelled')
        self.assertNotIn('collect', driver.calls)

    def test_timeout_stops_and_does_not_collect(self):
        now = [0.0]; driver = Driver(code=None)
        result = life.supervise(driver, life.Attempt(), 1, clock=lambda: now[0],
                                pause=lambda seconds: now.__setitem__(0, now[0] + seconds))
        self.assertEqual(result['outcome'], 'timeout')
        self.assertEqual(driver.calls[-2:], ['stop', 'verify_stopped'])

    def test_repeat_and_illegal_transitions_rejected(self):
        attempt = life.Attempt(); life.supervise(Driver(), attempt, 60)
        with self.assertRaises(ValueError): life.supervise(Driver(), attempt, 60)
        with self.assertRaises(ValueError): life.Attempt().move(life.Phase.RUNNING)

    def test_bounded_timeout_and_status_types(self):
        for timeout in (True, 0, 2401, -1):
            with self.assertRaises(ValueError): life.supervise(Driver(), life.Attempt(), timeout)
        for code in (True, '0', 300):
            result = life.supervise(Driver(code=code), life.Attempt(), 60)
            self.assertEqual(result['outcome'], 'infrastructure_failure')

    def test_late_cleanup_proof_is_quarantined(self):
        now = [0.0]; driver = Driver()
        def slow_stop(deadline): driver.call('stop'); now[0] = deadline + 1
        driver.stop = slow_stop
        result = life.supervise(driver, life.Attempt(), 60, clock=lambda: now[0])
        self.assertEqual(result['outcome'], 'cleanup_failure')
        self.assertNotIn('collect', driver.calls)

    def test_late_collection_does_not_pass(self):
        now = [0.0]; driver = Driver()
        def slow_collect(code, deadline): now[0] = deadline + 1; return {'passed': True}
        driver.collect = slow_collect
        result = life.supervise(driver, life.Attempt(), 60, clock=lambda: now[0])
        self.assertEqual(result['outcome'], 'report_failure')
        self.assertFalse(result['passed'])

    def test_cancellation_after_process_exit_cannot_pass(self):
        driver = Driver()
        result = life.supervise(driver, life.Attempt(), 60,
                                cancelled=lambda: 'verify_stopped' in driver.calls)
        self.assertEqual(result['outcome'], 'cancelled')
        self.assertFalse(result['passed'])
        self.assertNotIn('collect', driver.calls)


if __name__ == '__main__': unittest.main()
