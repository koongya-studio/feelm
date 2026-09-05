#!/usr/bin/env python3
"""Reviewed offline lifecycle fixture; not an untrusted-code supervisor or production monitor."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import uuid

HERE = Path(__file__).resolve().parent
CASES = ('valid', 'missing', 'modified', 'watchdog')
spec = importlib.util.spec_from_file_location('failure_check', HERE / 'ci-guard-failure-check.py')
support = importlib.util.module_from_spec(spec)
spec.loader.exec_module(support)

SUPERVISOR = r'''
import importlib.util, json, os, socket, subprocess, sys, time
from pathlib import Path
sys.dont_write_bytecode = True
base = Path(__file__).resolve().parent
case, host = sys.argv[1:]
if case not in ('valid', 'missing', 'modified', 'watchdog'):
    raise RuntimeError('Unknown fixed fixture case')
def offline():
    if os.readlink('/proc/self/ns/net') == host or {n for _, n in socket.if_nameindex()} != {'lo'}:
        raise RuntimeError('Expected offline private namespace with loopback only')
offline()  # Must run before any nft mutation.
def load(name, file):
    spec = importlib.util.spec_from_file_location(name, base / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module
service = load('service', 'ci-guard-service.py')
guard = load('guard', 'ci-network-guard.py')
expected = service.reference(guard.rules(1001, ['192.0.2.1', '192.0.2.2']))
if service.exists():
    raise RuntimeError('Expected empty fixture namespace')
service.execute(service.NFT, '--file', '-', input=guard.rules(1001, ['192.0.2.1', '192.0.2.2']))
service.verify(expected)
def notify(message):
    address = os.environ['NOTIFY_SOCKET']
    if address.startswith('@'):
        address = '\0' + address[1:]
    with socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM) as sock:
        sock.connect(address)
        sock.sendall(message.encode())
child = subprocess.Popen(['/bin/sleep', '60'], start_new_session=True,
                         env={'PATH': '/usr/bin:/bin'})
stamp = Path('/proc/' + str(child.pid) + '/stat').read_text().rpartition(')')[2].split()[19]
evidence = {'child_pid': child.pid, 'child_start': stamp, 'netns': os.readlink('/proc/self/ns/net'),
            'initial_verified': True, 'offline': True, 'outcome': 'started'}
def record():
    (base / (case + '.json')).write_text(json.dumps(evidence))
record()
notify('READY=1\nWATCHDOG=1')
for _ in range(4):
    service.verify(expected)
    notify('WATCHDOG=1')
    time.sleep(0.2)
if case == 'watchdog':
    evidence['outcome'] = 'heartbeat_stopped'
    record()
    time.sleep(60)  # systemd must terminate supervisor and child; no Python cleanup hides failure.
    raise RuntimeError('Watchdog did not stop fixture')
if case == 'missing':
    service.execute(service.NFT, 'delete', 'table', 'inet', 'feelm_ci_222')
elif case == 'modified':
    service.execute(service.NFT, 'add', 'rule', 'inet', 'feelm_ci_222', 'restricted', 'accept')
offline()  # Interface isolation still exists after the fixture firewall is removed/changed.
try:
    service.verify(expected)
except subprocess.CalledProcessError:
    if case != 'missing' or service.exists():
        raise
    evidence['outcome'] = 'missing_detected'
except ValueError as exc:
    if not str(exc).startswith('GUARD_MISMATCH:'):
        raise
    evidence['outcome'] = 'mismatch_detected'
else:
    if case != 'valid':
        raise RuntimeError('Drift was accepted')
    child.terminate()
    child.wait(timeout=5)
    evidence['outcome'] = 'completed'
record()
raise SystemExit(0 if case == 'valid' else 1)
'''


def command(unit, folder, case, host):
    return ['/usr/bin/systemd-run', '--unit=' + unit, '--service-type=notify',
            '--property=PrivateNetwork=yes', '--property=NotifyAccess=main',
            '--property=WatchdogSec=3', '--property=WatchdogSignal=SIGKILL',
            '--property=KillMode=control-group', '--property=Restart=no',
            '--property=RuntimeMaxSec=20', '--property=TimeoutStartSec=30',
            '--property=TimeoutStopSec=3', '--property=LimitCORE=0',
            '--property=MemoryMax=256M', '--property=TasksMax=64', '--property=CPUQuota=100%',
            '/usr/bin/python3', '-I', '-B', str(folder / 'supervisor.py'), case, host]


def same_process(pid, stamp):
    try:
        fields = Path(f'/proc/{pid}/stat').read_text().rpartition(')')[2].split()
        return fields[19] == stamp and fields[0] != 'Z'
    except FileNotFoundError:
        return False


def evaluate(case, state, evidence, host, child_alive):
    outcomes = {'valid': 'completed', 'missing': 'missing_detected',
                'modified': 'mismatch_detected', 'watchdog': 'heartbeat_stopped'}
    result = 'success' if case == 'valid' else ('watchdog' if case == 'watchdog' else 'exit-code')
    status = 'inactive' if case == 'valid' else 'failed'
    if (evidence.get('outcome') != outcomes[case] or evidence.get('netns') in (None, host)
            or evidence.get('initial_verified') is not True or evidence.get('offline') is not True
            or state.get('ActiveState') != status or state.get('Result') != result
            or state.get('MainPID') != '0' or child_alive):
        raise ValueError('Runtime result/evidence mismatch; timeout or startup failure is not a pass')


def cleanup_unit(unit):
    # A successful notify service may already have been garbage-collected by systemd.
    def status():
        data = support.execute('/usr/bin/systemctl', 'show', unit,
                               '--property=LoadState,ActiveState,MainPID').stdout
        return dict(line.split('=', 1) for line in data.splitlines() if '=' in line)
    state = status()
    if state.get('LoadState') == 'not-found':
        return
    support.execute('/usr/bin/systemctl', 'stop', unit, check=False)
    state = status()
    if state.get('LoadState') == 'not-found':
        return
    if state.get('ActiveState') not in ('inactive', 'failed') or state.get('MainPID') != '0':
        raise ValueError('Disposable unit still active')
    support.execute('/usr/bin/systemctl', 'reset-failed', unit, check=False)


def run():
    if sys.platform != 'linux' or os.geteuid() != 0 or Path('/proc/1/comm').read_text().strip() != 'systemd':
        raise ValueError('Linux root and system systemd required')
    folder = Path(tempfile.mkdtemp(prefix='ci222-runtime-', dir='/run'))
    print('RUNTIME_RUN_DIR: ' + str(folder), flush=True)
    host = os.readlink('/proc/self/ns/net')
    units, errors = [], []
    try:
        for name in ('ci-guard-service.py', 'ci-network-guard.py'):
            shutil.copyfile(HERE / name, folder / name)
            (folder / name).chmod(0o600)
        (folder / 'supervisor.py').write_text(SUPERVISOR)
        for case in CASES:
            unit = 'ci222-runtime-' + uuid.uuid4().hex + '-' + case + '.service'
            units.append(unit)
            print('DISPOSABLE_UNIT: ' + unit, flush=True)
            support.execute(*command(unit, folder, case, host))
            deadline = time.monotonic() + 35
            while True:
                output = support.execute('/usr/bin/systemctl', 'show', unit,
                                         '--property=ActiveState,Result,MainPID,ExecMainStatus').stdout
                state = dict(line.split('=', 1) for line in output.splitlines() if '=' in line)
                if state.get('ActiveState') in ('inactive', 'failed'):
                    break
                if time.monotonic() >= deadline:
                    raise ValueError('Fixture did not stop before deadline')
                time.sleep(0.2)
            evidence = json.loads((folder / (case + '.json')).read_text())
            alive = same_process(evidence['child_pid'], evidence['child_start'])
            (folder / (case + '.result.json')).write_text(json.dumps({'unit': unit, 'state': state,
                                                                        'child_alive': alive}, indent=2))
            evaluate(case, state, evidence, host, alive)
            print(case.upper() + ': PASS; child stopped', flush=True)
    finally:
        for unit in units:
            try:
                cleanup_unit(unit)
            except (ValueError, OSError, subprocess.SubprocessError):
                errors.append(unit)
        print('CLEANUP_REQUIRED: ' + json.dumps(errors), flush=True)
    if errors:
        raise ValueError('Cleanup incomplete')
    print('RUNTIME_CHECK: PASS; OFFLINE_FIXTURE_ONLY; NOT_RUNNER_ISOLATION_APPROVAL')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['plan', 'run'], nargs='?', default='plan')
    parser.add_argument('--confirm', action='store_true')
    args = parser.parse_args()
    if args.action == 'plan':
        print('PLAN: four disposable offline services; fixed sleep workload; guard drift and watchdog; '
              'no operating config, Docker, credentials, MR code or external network')
        return
    if not args.confirm:
        parser.error('run requires --confirm')
    run()


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, support.interrupted)
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.SubprocessError, KeyboardInterrupt) as exc:
        print('RUNTIME_CHECK: FAIL: ' + str(exc), file=sys.stderr)
        sys.exit(2)
