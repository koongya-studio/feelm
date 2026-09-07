#!/usr/bin/env python3
"""리뷰 Runner의 서비스 sandbox에서 비밀값 없이 실행하는 일회성 검사다."""
import os
from pathlib import Path
try:
    import pwd
except ImportError:  # Windows에서는 제한값 검증만 테스트하며 실제 실행은 Linux 전용이다.
    pwd = None
import re
import socket
import ssl
import subprocess
import tempfile

ACCOUNT = 'feelm-ci-review'
HOME = Path('/home') / ACCOUNT


def limits_match(values):
    quota, period = values['cpu.max'].split()
    return (quota.isdecimal() and period.isdecimal() and int(period) > 0
            and int(quota) == int(period)
            and values['memory.high'] == str(2 * 1024**3)
            and values['memory.max'] == str(3 * 1024**3)
            and values['memory.swap.max'] == '0'
            and values['pids.max'] == '128')


def cgroup_limits():
    groups = Path('/proc/self/cgroup').read_text().splitlines()
    paths = [line[3:] for line in groups if line.startswith('0::')]
    if len(paths) != 1 or '..' in Path(paths[0]).parts:
        raise ValueError('Expected unified cgroup')
    base = Path('/sys/fs/cgroup') / paths[0].lstrip('/')
    return {key: (base / key).read_text().strip() for key in
            ['cpu.max', 'memory.high', 'memory.max', 'memory.swap.max', 'pids.max']}


def check(name, test):
    try:
        passed = bool(test())
    except Exception as exc:
        print('FAIL:', name, type(exc).__name__, flush=True)
        return False
    print(('PASS: ' if passed else 'FAIL: ') + name, flush=True)
    return passed


def identity():
    user = pwd.getpwnam(ACCOUNT)
    return (os.getuid() == user.pw_uid != 0 and os.getgid() == user.pw_gid
            and set(os.getgroups()) <= {user.pw_gid} and os.environ.get('HOME') == str(HOME))


def privileges():
    values = dict(line.split(':', 1) for line in Path('/proc/self/status').read_text().splitlines()
                  if ':' in line)
    return (values['NoNewPrivs'].strip() == '1'
            and all(int(values[key].strip(), 16) == 0 for key in ['CapEff', 'CapPrm', 'CapBnd']))


def home_writable():
    with tempfile.TemporaryFile(dir=HOME) as output:
        output.write(b'review sandbox probe\n')
    return True


def sockets_blocked():
    for path in ['/run/docker.sock', '/run/containerd/containerd.sock',
                 '/run/dbus/system_bus_socket', '/run/systemd/private']:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
            sock.settimeout(1)
            try:
                sock.connect(path)
            except OSError:
                continue
            return False
    return True


def claude_version():
    result = subprocess.run([str(HOME / '.local/bin/claude'), '--version'],
                            capture_output=True, text=True, timeout=30, check=True)
    match = re.fullmatch(r'(\d+\.\d+\.\d+) \(Claude Code\)', result.stdout.strip())
    if not match:
        return False
    print('CLAUDE_VERSION:', match.group(1), flush=True)
    return match.group(1) == '2.1.236'


def tls(host):
    with socket.create_connection((host, 443), timeout=5) as sock:
        with ssl.create_default_context().wrap_socket(sock, server_hostname=host):
            return True


def main():
    checks = [
        ('dedicated account', identity),
        ('no privilege escalation or capabilities', privileges),
        ('cgroup CPU/memory/process limits', lambda: limits_match(cgroup_limits())),
        ('other home directories hidden', lambda: set(os.listdir('/home')) == {ACCOUNT}),
        ('system filesystem read-only', lambda: os.statvfs('/etc').f_flag & os.ST_RDONLY),
        ('review home writable', home_writable),
        ('control sockets unavailable (absent or denied)', sockets_blocked),
        ('Claude CLI version without authentication', claude_version),
        ('GitLab TLS without authentication', lambda: tls('lab.ssafy.com')),
        ('Claude API TLS without authentication', lambda: tls('api.anthropic.com')),
    ]
    results = [check(name, test) for name, test in checks]
    print('SANDBOX_PROBE: ' + ('PASS' if all(results) else 'FAIL'), flush=True)
    return 0 if all(results) else 1


if __name__ == '__main__':
    raise SystemExit(main())
