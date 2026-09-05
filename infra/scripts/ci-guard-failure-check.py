#!/usr/bin/env python3
"""Exercise real systemd ExecStartPre rejection in disposable PrivateNetwork services."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import uuid

HERE = Path(__file__).resolve().parent
ENV = {'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LANG': 'C', 'LC_ALL': 'C'}
CASES = ('valid', 'missing', 'modified')

# Test fixture only: production account/config/resource validation is not replaced or patched.
# The same production reference()/verify() functions are executed without mocking nft.
GATE = r'''
import importlib.util, json, os, subprocess, sys
from pathlib import Path
sys.dont_write_bytecode = True
base = Path(__file__).resolve().parent
case, host = sys.argv[1:]
if case not in ('valid', 'missing', 'modified') or os.readlink('/proc/self/ns/net') == host:
    raise RuntimeError('Refusing fixture creation outside isolated network')
def load(name, file):
    spec = importlib.util.spec_from_file_location(name, base / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module
service = load('service', 'ci-guard-service.py')
guard = load('guard', 'ci-network-guard.py')
source = guard.rules(1001, ['192.0.2.1', '192.0.2.2'])
expected = service.reference(source)
if service.exists():
    raise RuntimeError('Fixture namespace is not empty')
if case != 'missing':
    service.execute(service.NFT, '--file', '-', input=source)
if case == 'modified':
    service.execute(service.NFT, 'add', 'rule', 'inet', 'feelm_ci_222', 'restricted', 'accept')
try:
    service.verify(expected)
except subprocess.CalledProcessError:
    if case != 'missing' or service.exists():
        raise
    outcome = 'missing'
except ValueError as exc:
    if not str(exc).startswith('GUARD_MISMATCH:'):
        raise
    outcome = 'mismatch'
else:
    outcome = 'verified'
(base / (case + '.gate.json')).write_text(json.dumps({'outcome': outcome,
    'netns': os.readlink('/proc/self/ns/net')}))
raise SystemExit(0 if outcome == 'verified' else 1)
'''

WORKLOAD = r'''
import os, sys
from pathlib import Path
if os.readlink('/proc/self/ns/net') == sys.argv[1]:
    raise RuntimeError('Refusing host namespace workload')
Path(sys.argv[2]).write_text('WORKLOAD_EXECUTED\n')
'''


def execute(*args, check=True):
    return subprocess.run(args, text=True, capture_output=True, timeout=120, check=check, env=ENV, cwd='/')


def command(unit, folder, case, host):
    return ['/usr/bin/systemd-run', '--unit=' + unit, '--service-type=oneshot',
            '--property=RemainAfterExit=yes', '--property=PrivateNetwork=yes',
            '--property=TimeoutStartSec=60', '--property=TimeoutStopSec=10',
            '--property=MemoryMax=256M', '--property=TasksMax=64', '--property=CPUQuota=100%',
            f'--property=ExecStartPre=/usr/bin/python3 -I -B {folder}/gate.py {case} {host}',
            '/usr/bin/python3', '-I', '-B', str(folder / 'workload.py'), host,
            str(folder / (case + '.started'))]


def evaluate(case, result, state, evidence, started, host):
    expected = {'valid': 'verified', 'missing': 'missing', 'modified': 'mismatch'}[case]
    if evidence.get('outcome') != expected or evidence.get('netns') in (None, host):
        raise ValueError('Missing/wrong gate evidence; infrastructure failure is not a passing negative test')
    if case == 'valid':
        ok = (result == 0 and state.get('ActiveState') == 'active'
              and state.get('Result') == 'success' and started)
    else:
        ok = (result != 0 and state.get('ActiveState') == 'failed'
              and state.get('Result') == 'exit-code' and not started
              and state.get('ExecMainStartTimestampMonotonic') == '0'
              and re.search(r'(?:^|[ ;])status=1(?:[ ;}]|$)', state.get('ExecStartPre', '')) is not None)
    if not ok:
        raise ValueError('Unexpected service/workload result')


def run():
    if sys.platform != 'linux' or os.geteuid() != 0:
        raise ValueError('Linux root and running system systemd required')
    if Path('/proc/1/comm').read_text().strip() != 'systemd':
        raise ValueError('PID 1 must be systemd')
    for binary in ('/usr/bin/systemd-run', '/usr/bin/unshare', '/usr/sbin/nft'):
        if not Path(binary).is_file():
            raise ValueError('Required Linux tool missing')
    host = os.readlink('/proc/self/ns/net')
    folder = Path(tempfile.mkdtemp(prefix='ci222-failcheck-', dir='/run'))
    print('FAILCHECK_RUN_DIR: ' + str(folder), flush=True)
    units, cleanup_errors = [], []
    try:
        for name in ('ci-guard-service.py', 'ci-network-guard.py'):
            shutil.copyfile(HERE / name, folder / name)
            (folder / name).chmod(0o600)
        (folder / 'gate.py').write_text(GATE)
        (folder / 'workload.py').write_text(WORKLOAD)
        for case in CASES:
            unit = 'ci222-failcheck-' + uuid.uuid4().hex + '-' + case + '.service'
            units.append(unit)
            print('DISPOSABLE_UNIT: ' + unit, flush=True)
            result = execute(*command(unit, folder, case, host), check=False)
            data = execute('/usr/bin/systemctl', 'show', unit, '--property=ActiveState,Result,'
                           'ExecStartPre,ExecMainStartTimestampMonotonic').stdout
            state = dict(line.split('=', 1) for line in data.splitlines() if '=' in line)
            (folder / (case + '.result.json')).write_text(json.dumps({
                'unit': unit, 'returncode': result.returncode, 'state': state,
                'stdout': result.stdout, 'stderr': result.stderr}, indent=2))
            evidence = json.loads((folder / (case + '.gate.json')).read_text())
            evaluate(case, result.returncode, state, evidence, (folder / (case + '.started')).exists(), host)
            print(case.upper() + ': PASS' + (' (workload ran)' if case == 'valid' else ' (workload blocked)'), flush=True)
    finally:
        # Only UUID units allocated by this invocation. No glob, user@, Docker or guard-service stop.
        for unit in units:
            try:
                execute('/usr/bin/systemctl', 'stop', unit)
                state = execute('/usr/bin/systemctl', 'show', unit, '--property=ActiveState', '--value').stdout.strip()
                if state not in ('inactive', 'failed'):
                    raise ValueError('Disposable service remains active')
                execute('/usr/bin/systemctl', 'reset-failed', unit, check=False)
            except (ValueError, OSError, subprocess.SubprocessError):
                cleanup_errors.append(unit)
        if cleanup_errors:
            print('CLEANUP_REQUIRED: ' + json.dumps(cleanup_errors), flush=True)
        else:
            print('DISPOSABLE_UNITS_STOPPED: PASS; evidence retained in /run until reboot', flush=True)
    if cleanup_errors:
        raise ValueError('Cleanup did not complete')
    print('FAILURE_GATE_CHECK: PASS; NOT_RUNNER_ISOLATION_APPROVAL')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['plan', 'run'], nargs='?', default='plan')
    parser.add_argument('--confirm', action='store_true')
    args = parser.parse_args()
    if args.action == 'plan':
        print('PLAN: three disposable root services with PrivateNetwork; valid/missing/modified guard; '
              'no operating firewall changes; no Docker/Runner restart; retain /run evidence')
        return
    if not args.confirm:
        parser.error('run requires --confirm to create disposable system services')
    run()


def interrupted(signum, frame):
    raise KeyboardInterrupt('Interrupted; stop disposable units in finally')


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, interrupted)
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.SubprocessError, KeyboardInterrupt) as exc:
        print('FAILURE_GATE_CHECK: FAIL: ' + str(exc), file=sys.stderr)
        sys.exit(2)
