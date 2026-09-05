"""Trusted orchestration contract, not a GitLab driver or a sandbox by itself.

Driver methods must be bounded and own exactly one attempt. Kernel-enforced
runtime limits and external supervisor-death recovery remain driver duties.
MR data cannot supply a Driver, cancellation authority, or cleanup proof.
"""
from dataclasses import dataclass, field
from enum import Enum
import time


class Phase(str, Enum):
    NEW = 'new'
    PREPARING = 'preparing'
    PREPARED = 'prepared'
    STARTING = 'starting'
    RUNNING = 'running'
    STOPPING = 'stopping'
    STOPPED = 'stopped'
    COLLECTING = 'collecting'
    FINISHED = 'finished'
    QUARANTINED = 'quarantined'


ALLOWED = {
    Phase.NEW: {Phase.PREPARING},
    Phase.PREPARING: {Phase.PREPARED, Phase.STOPPING},
    Phase.PREPARED: {Phase.STARTING, Phase.STOPPING},
    Phase.STARTING: {Phase.RUNNING, Phase.STOPPING},
    Phase.RUNNING: {Phase.STOPPING},
    Phase.STOPPING: {Phase.STOPPED, Phase.QUARANTINED},
    Phase.STOPPED: {Phase.COLLECTING, Phase.FINISHED},
    Phase.COLLECTING: {Phase.FINISHED, Phase.QUARANTINED},
    Phase.FINISHED: set(), Phase.QUARANTINED: set(),
}


@dataclass
class Attempt:
    phase: Phase = Phase.NEW
    events: list = field(default_factory=lambda: [Phase.NEW.value])
    process_exit: int | None = None
    outcome: str = 'incomplete'
    # This object lives in the trusted supervisor, never in a job-writeable root.
    def move(self, target):
        if target not in ALLOWED[self.phase]:
            raise ValueError('Illegal lifecycle transition')
        self.phase = target
        self.events.append(target.value)

    def result(self):
        return {'phase': self.phase.value, 'outcome': self.outcome,
                'process_exit': self.process_exit, 'events': list(self.events),
                'passed': self.phase == Phase.FINISHED and self.outcome == 'success',
                'not_isolation_approval': True}


class Abort(Exception):
    def __init__(self, reason): self.reason = reason


def supervise(driver, attempt, timeout_seconds, cancelled=lambda: False,
              clock=time.monotonic, pause=time.sleep):
    """Single-use attempt; stop+proof always precede artifact collection.

    prepare(deadline), start(deadline), poll(deadline), stop(deadline),
    verify_stopped(deadline), collect(process_exit, deadline) are trusted driver
    methods. verify_stopped MUST raise unless all owned processes/cgroups/mounts
    are gone. A driver timeout is an infrastructure failure, not a test pass.
    The loop timeout does not replace an independent service hard deadline.
    """
    if attempt.phase != Phase.NEW or type(timeout_seconds) is not int or not 1 <= timeout_seconds <= 2400:
        raise ValueError('New attempt and bounded timeout required')
    deadline = clock() + timeout_seconds

    def check():
        if cancelled(): raise Abort('cancelled')
        if clock() >= deadline: raise Abort('timeout')

    attempt.move(Phase.PREPARING)
    try:
        check(); driver.prepare(deadline); check()
        attempt.move(Phase.PREPARED)
        attempt.move(Phase.STARTING)
        driver.start(deadline); check()
        attempt.move(Phase.RUNNING)
        while True:
            check()
            code = driver.poll(deadline)
            check()
            if code is not None:
                if type(code) is not int or not -255 <= code <= 255:
                    raise ValueError('Invalid trusted process status')
                attempt.process_exit = code
                attempt.outcome = 'success' if code == 0 else 'test_failure'
                break
            pause(min(0.1, max(0, deadline - clock())))
    except Abort as exc:
        attempt.outcome = exc.reason
    except Exception:
        # Do not publish arbitrary exception text (paths, tokens, MR stdout).
        attempt.outcome = 'infrastructure_failure'
    finally:
        attempt.move(Phase.STOPPING)
        try:
            cleanup_deadline = clock() + 30
            driver.stop(cleanup_deadline)
            if clock() >= cleanup_deadline: raise TimeoutError('Cleanup deadline')
            driver.verify_stopped(cleanup_deadline)
            if clock() >= cleanup_deadline: raise TimeoutError('Cleanup proof deadline')
        except Exception:
            attempt.outcome = 'cleanup_failure'
            attempt.move(Phase.QUARANTINED)
        else:
            attempt.move(Phase.STOPPED)
    if attempt.phase == Phase.QUARANTINED: return attempt.result()
    if cancelled(): attempt.outcome = 'cancelled'
    if attempt.process_exit is None or attempt.outcome == 'cancelled':
        attempt.move(Phase.FINISHED)
        return attempt.result()
    attempt.move(Phase.COLLECTING)
    try:
        collection_deadline = clock() + 30
        report = driver.collect(attempt.process_exit, collection_deadline)
        if clock() >= collection_deadline: raise TimeoutError('Collection deadline')
        if not isinstance(report, dict) or type(report.get('passed')) is not bool:
            raise ValueError('Invalid collector result')
        if not report['passed'] and attempt.outcome == 'success':
            attempt.outcome = 'report_failure'
        if cancelled(): attempt.outcome = 'cancelled'
        attempt.move(Phase.FINISHED)
    except Exception:
        attempt.outcome = 'report_failure'
        attempt.move(Phase.QUARANTINED)
    return attempt.result()
