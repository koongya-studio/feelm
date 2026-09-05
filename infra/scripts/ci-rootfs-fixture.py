#!/usr/bin/env python3
"""Local WSL fixed rootfs fixture; optional bounded Docker startup attempt. Never an MR runner."""
import argparse
import ctypes
import errno
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile

PROBE = r'''set -eu
B=/bin/busybox
test "$($B id -u)" = 0
test "$($B awk 'NR==1 {print $1,$2,$3}' /proc/self/uid_map)" = '0 65534 1'
for key in CapEff CapPrm CapBnd CapAmb; do
  test "$($B awk -v k="$key:" '$1==k {print $2}' /proc/self/status)" = 0000000000000000
done
test "$($B awk '$1=="NoNewPrivs:" {print $2}' /proc/self/status)" = 1
for target in /home /run/user /var/run/docker.sock /sys/fs/cgroup /mnt/c; do
  test ! -e "$target"
done
test ! -e "$CANARY"
test ! -e "/proc/1/root$CANARY"
test ! -e "$HOST_SOCKET"
test ! -e /proc/1/root/run/user
test "$($B readlink /proc/self/ns/net)" != "$HOST_NET"
test "$($B readlink /proc/self/ns/pid)" != "$HOST_PID"
test "$($B readlink /proc/self/ns/mnt)" != "$HOST_MNT"
if message=$($B mount -t tmpfs none /scratch 2>&1); then exit 21; fi
case "$message" in
  *"permission denied"*|*"Operation not permitted"*) ;;
  *) printf 'UNEXPECTED_MOUNT_ERROR: %s\n' "$message"; exit 23 ;;
esac
if message=$($B touch /host-write-test 2>&1); then exit 22; fi
case "$message" in
  *"Permission denied"*|*"Operation not permitted"*) ;;
  *) printf 'UNEXPECTED_WRITE_ERROR: %s\n' "$message"; exit 24 ;;
esac
test "$($B awk 'NR>2 {print $1}' /proc/net/dev)" = 'lo:'
printf 'ROOTFS_FIXED_PROBE: PASS\n'
printf 'HOST_PATHS_AND_CANARY: ABSENT\n'
printf 'CAPABILITIES: ZERO; NO_NEW_PRIVS: 1\n'
printf 'NOT_DOCKER_OR_MR_APPROVAL\n'
'''

DOCKER_PROBE = r'''
printf 'DOCKER_VERSION: '
/bin/dockerd --version
set +e
/bin/busybox timeout -s TERM 12 /bin/dockerd --rootless \
  --data-root=/scratch/data --exec-root=/scratch/exec \
  --pidfile=/scratch/docker.pid --host=unix:///scratch/docker.sock \
  > /scratch/daemon.log 2>&1
result=$?
set -e
/bin/busybox cat /scratch/daemon.log
printf 'DAEMON_ATTEMPT_EXIT=%s\n' "$result"
printf 'DOCKER_COMPATIBILITY: NOT_PASSED; no API/container success established\n'
exit 2
'''


def drop_caps():
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(38, 1, 0, 0, 0) != 0:  # PR_SET_NO_NEW_PRIVS
        raise OSError(ctypes.get_errno(), 'no_new_privs')
    for cap in range(64):
        if libc.prctl(24, cap, 0, 0, 0) != 0 and ctypes.get_errno() != errno.EINVAL:
            raise OSError(ctypes.get_errno(), 'cap bounding drop')
    class Header(ctypes.Structure):
        _fields_ = [('version', ctypes.c_uint32), ('pid', ctypes.c_int)]
    class Data(ctypes.Structure):
        _fields_ = [('effective', ctypes.c_uint32), ('permitted', ctypes.c_uint32),
                    ('inheritable', ctypes.c_uint32)]
    if libc.capset(ctypes.byref(Header(0x20080522, 0)), ctypes.byref((Data * 2)())) != 0:
        raise OSError(ctypes.get_errno(), 'capset')


def setup(root):
    # Internal fixed child only; do not mount anything unless user mapping is isolated.
    if Path('/proc/self/uid_map').read_text().splitlines()[0].split() != ['0', '65534', '1']:
        raise ValueError('Expected unprivileged outer UID mapping')
    if os.readlink('/proc/self/ns/mnt') == os.environ['HOST_MNT']:
        raise ValueError('Mount namespace was not isolated')
    subprocess.run(['/usr/bin/mount', '--make-rprivate', '/'], check=True)
    subprocess.run(['/usr/bin/mount', '-t', 'proc', '-o', 'nosuid,nodev,noexec',
                    'proc', str(root / 'proc')], check=True)
    os.chroot(root)
    os.chdir('/')
    drop_caps()
    os.closerange(3, 65536)
    os.execve('/bin/busybox', ['/bin/busybox', 'sh', '/probe.sh'], dict(os.environ))


def command(script, root):
    return ['/usr/bin/setpriv', '--reuid=65534', '--regid=65534', '--clear-groups',
            '/usr/bin/unshare', '--user', '--map-root-user', '--mount', '--pid', '--fork',
            '--kill-child=KILL', '--net', '--ipc', '--uts', '--propagation=private',
            '/usr/bin/python3', '-I', '-B', str(script), '_setup', str(root)]


def run(docker_tools=None):
    if os.geteuid() != 0:
        raise ValueError('Local fixture orchestrator requires root; workload drops to outer UID 65534')
    # Restrict actual execution to the explicitly approved local WSL environment.
    if 'microsoft' not in Path('/proc/sys/kernel/osrelease').read_text().lower():
        raise ValueError('Local WSL fixture only; do not run on service servers')
    binary = Path('/usr/bin/busybox')
    description = subprocess.check_output(['/usr/bin/file', str(binary)], text=True)
    if 'statically linked' not in description:
        raise ValueError('Existing static busybox required; no package installation')
    directory = Path(tempfile.mkdtemp(prefix='ci222-rootfs-'))
    directory.chmod(0o755)
    root = directory / 'root'
    root.mkdir(mode=0o755)
    for name in ('bin', 'proc', 'scratch'):
        (root / name).mkdir(mode=0o755)
    shutil.copyfile(binary, root / 'bin/busybox')
    (root / 'bin/busybox').chmod(0o755)
    if docker_tools is not None:
        tools = Path(docker_tools)
        if not tools.is_absolute() or tools.resolve() != tools or not str(tools).startswith('/tmp/ci222-docker-tools-'):
            raise ValueError('Expected previously prepared local static tools directory')
        for name in ('dockerd', 'docker', 'containerd', 'containerd-shim-runc-v2', 'runc', 'docker-init', 'docker-proxy'):
            source = tools / name
            if source.is_symlink() or not source.is_file() or source.stat().st_uid != 0 or source.stat().st_mode & 0o022:
                raise ValueError('Expected root-owned static tool: ' + name +
                    (' missing' if not source.exists() else
                     ' uid=' + str(source.stat().st_uid) + ' mode=' + oct(source.stat().st_mode)))
            shutil.copyfile(source, root / 'bin' / name)
            (root / 'bin' / name).chmod(0o755)
        # Only synthetic runtime/data folders become writable; host paths remain absent.
        for name in ('scratch', 'run', 'tmp'):
            (root / name).mkdir(exist_ok=True, mode=0o700)
            os.chown(root / name, 65534, 65534)
        (root / 'etc').mkdir(mode=0o755)
        (root / 'dev').mkdir(mode=0o755)
        os.mknod(root / 'dev/null', 0o20666, os.makedev(1, 3))
        (root / 'bin/sh').symlink_to('busybox')
    (root / 'probe.sh').write_text(PROBE + (DOCKER_PROBE if docker_tools else ''))
    (root / 'probe.sh').chmod(0o644)
    script = directory / 'fixture.py'
    shutil.copyfile(__file__, script)
    script.chmod(0o644)
    canary = directory / 'host-canary'
    canary.write_text('synthetic fixture only; not a secret')
    canary.chmod(0o644)
    listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    host_socket = directory / 'host.sock'
    listener.bind(str(host_socket))
    listener.listen(1)
    host_socket.chmod(0o666)
    env = {'PATH': '/usr/bin:/bin', 'LANG': 'C', 'CANARY': str(canary),
           'HOST_SOCKET': str(host_socket)}
    if docker_tools:
        env.update({'HOME': '/scratch', 'XDG_RUNTIME_DIR': '/run'})
    env.update({'HOST_' + k.upper(): os.readlink('/proc/self/ns/' + k) for k in ('net', 'pid', 'mnt')})
    print('LOCAL_EVIDENCE_DIR: ' + str(directory), flush=True)
    try:
        result = subprocess.run(command(script, root), env=env, cwd='/', close_fds=True,
                                text=True, capture_output=True, timeout=30)
        print(result.stdout, end='')
        print(result.stderr, end='', file=sys.stderr)
        passed = result.returncode == 0 and 'ROOTFS_FIXED_PROBE: PASS' in result.stdout
        (directory / 'result.json').write_text(json.dumps({'exit': result.returncode,
            'passed': passed, 'stdout': result.stdout, 'stderr': result.stderr}, indent=2))
        print('LOCAL_ROOTFS_EXIT=' + ('0' if passed else '2'))
        return 0 if passed else 2
    finally:
        listener.close()
        # Evidence retained; mounts die with the namespace, no recursive host deletion.


def main():
    if len(sys.argv) == 3 and sys.argv[1] == '_setup':
        setup(Path(sys.argv[2]))
        return 2
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['plan', 'run'], nargs='?', default='plan')
    parser.add_argument('--confirm-local-fixture', action='store_true')
    parser.add_argument('--docker-tools')
    args = parser.parse_args()
    if args.action == 'plan':
        print('PLAN: WSL only; synthetic rootfs/canary/socket; mapped outer UID65534; '
              'private PID/mount/net; zero caps; retained evidence; no Docker/MR')
        return 0
    if not args.confirm_local_fixture:
        parser.error('run requires --confirm-local-fixture')
    return run(args.docker_tools)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, OSError, subprocess.SubprocessError) as exc:
        print('LOCAL_ROOTFS_ERROR: ' + str(exc), file=sys.stderr)
        sys.exit(2)
