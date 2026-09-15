#!/usr/bin/env python3
"""Fixed reviewed Docker pilot launcher; only inside systemd PrivateNetwork.

Not an untrusted-job sandbox. The account still owns its other Unix sockets.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys

BASE = Path('/home/feelm-ci-test/offline-docker-222')
RUNTIME = Path('/run/feelm-ci222-offline')
PATH = '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'


def environment():
    # Do not inherit proxy settings, Docker context, credentials or RootlessKit flags.
    return {
        'PATH': PATH, 'HOME': str(BASE), 'USER': 'feelm-ci-test',
        'LOGNAME': 'feelm-ci-test', 'XDG_RUNTIME_DIR': str(RUNTIME),
        'DBUS_SESSION_BUS_ADDRESS': 'unix:path=/run/user/1001/bus',
        'XDG_CONFIG_HOME': str(BASE / 'config'),
        'XDG_DATA_HOME': str(BASE / 'data'),
        'DOCKERD_ROOTLESS_ROOTLESSKIT_STATE_DIR': str(RUNTIME / 'rootlesskit'),
        'DOCKERD_ROOTLESS_ROOTLESSKIT_NET': 'slirp4netns',
        'DOCKERD_ROOTLESS_ROOTLESSKIT_PORT_DRIVER': 'builtin',
        'DOCKERD_ROOTLESS_ROOTLESSKIT_DETACH_NETNS': 'false',
    }


def command():
    return ['/usr/bin/dockerd-rootless.sh', '--rootless',
            '--config-file=/usr/local/lib/feelm-ci222/offline-daemon.json',
            '--data-root=' + (BASE / 'data/docker').as_posix(),
            '--exec-root=' + (RUNTIME / 'exec').as_posix(),
            '--pidfile=' + (RUNTIME / 'docker.pid').as_posix(),
            '--host=unix://' + (RUNTIME / 'docker.sock').as_posix()]


def validate_network(links, routes4, routes6):
    if len(links) != 1 or links[0].get('ifname') != 'lo':
        raise ValueError('Outer namespace must contain only loopback')
    if routes4 or routes6:
        raise ValueError('Outer namespace must have no non-local routes')


def read_ip(*args):
    result = subprocess.run(['/usr/sbin/ip', '-j', *args], check=True,
                            capture_output=True, text=True, timeout=10,
                            env={'PATH': PATH})
    return json.loads(result.stdout)


def check():
    import pwd  # Linux-only runtime checks; plan and pure tests are portable.
    if os.getuid() != 1001 or pwd.getpwuid(1001).pw_name != 'feelm-ci-test':
        raise ValueError('Dedicated UID 1001 required')
    if set(os.getgroups()) - {1001}:
        raise ValueError('Unexpected supplementary groups')
    for target in (BASE, RUNTIME):
        info = target.lstat()
        if (not stat.S_ISDIR(info.st_mode) or info.st_uid != 1001
                or stat.S_IMODE(info.st_mode) != 0o700 or target.resolve() != target):
            raise ValueError('Expected dedicated real mode-700 directory')
    config = Path('/usr/local/lib/feelm-ci222/offline-daemon.json')
    for target in (Path(__file__).resolve(), config, config.parent):
        info = target.lstat()
        if target.is_symlink() or info.st_uid != 0 or info.st_mode & 0o022:
            raise ValueError('Launcher/config must be root owned and not writable by others')
    if json.loads(config.read_text()) != {'log-driver': 'local'}:
        raise ValueError('Unexpected daemon configuration')
    if shutil.disk_usage(BASE).free < 5 * 1024**3:
        raise ValueError('Need 5 GiB free; this is not a storage quota')
    for socket in ('/var/run/docker.sock', '/run/user/1001/docker.sock'):
        # Existing rootless socket access is intentionally NOT an isolation claim.
        if socket.startswith('/var/') and (os.access(socket, os.R_OK) or os.access(socket, os.W_OK)):
            raise ValueError('Operating Docker socket must be inaccessible')
    validate_network(read_ip('link', 'show'), read_ip('-4', 'route', 'show'),
                     read_ip('-6', 'route', 'show'))
    print('OUTER_NETWORK_CHECK: loopback only; no routes; reviewed pilot only', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['plan', 'check', 'launch'], nargs='?', default='plan')
    args = parser.parse_args()
    if args.action == 'plan':
        print(json.dumps({'command': command(), 'environment': environment(),
                          'requires': 'root-owned system service with PrivateNetwork=yes'}))
        return
    check()
    if args.action == 'launch':
        os.execve(command()[0], command(), environment())


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, subprocess.SubprocessError) as exc:
        print('OFFLINE_DOCKER_REFUSED: ' + str(exc), file=sys.stderr)
        sys.exit(2)
