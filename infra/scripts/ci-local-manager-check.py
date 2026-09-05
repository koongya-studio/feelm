#!/usr/bin/env python3
"""WSL-only namespace-manager experiment. No host daemon or service installation."""
import argparse
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tarfile
import tempfile
import time

SCRIPT = r'''#!/bin/sh
set -eu
export PATH=/bin:/usr/bin:/sbin:/usr/sbin
export DOCKER_HOST=unix:///run/probe-docker.sock
mkdir -p /data /run/docker /tmp
echo CGROUP_BEFORE
cat /proc/self/cgroup
for entry in /sys/fs/cgroup/cgroup.* /sys/fs/cgroup/*/cgroup.type; do
  case "$entry" in *cgroup.stat|*cgroup.events|*cgroup.threads) continue ;; esac
  echo "$entry"; cat "$entry" || true
done
if [ "${DIAGNOSE_ONLY:-0}" = 1 ]; then
  echo CGROUP_NAMESPACE
  /bin/busybox readlink /proc/self/ns/cgroup
  cat /proc/self/uid_map
  /bin/busybox grep cgroup /proc/self/mountinfo
  sleep 4
  exit 0
fi
# Keep the delegated parent empty: cgroup v2 domain controllers cannot be
# distributed from a non-root cgroup containing the manager process itself.
test "$(/bin/busybox readlink /proc/self/ns/cgroup)" != "$HOST_CGROUP_NAMESPACE"
read inner_uid outer_uid range_size < /proc/self/uid_map
test "$inner_uid" = 0
test "$outer_uid" -gt 0
test "$range_size" = 65536
test "$(cat /sys/fs/cgroup/cgroup.type)" = domain
mkdir /sys/fs/cgroup/manager
echo $$ > /sys/fs/cgroup/manager/cgroup.procs
echo '+cpu +memory +pids' > /sys/fs/cgroup/cgroup.subtree_control
test -z "$(cat /sys/fs/cgroup/cgroup.procs)"
echo CGROUP_MANAGER_LEAF_READY
dockerd --data-root=/data --exec-root=/run/exec --pidfile=/run/probe.pid \
  --host="$DOCKER_HOST" >/run/daemon.log 2>&1 &
daemon=$!
trap 'kill "$daemon" 2>/dev/null || true; wait "$daemon" || true' EXIT
ready=no
for i in $(seq 1 40); do
  if docker info >/run/info 2>&1; then ready=yes; break; fi
  sleep 1
done
if [ "$ready" != yes ]; then tail -n 30 /run/daemon.log; exit 2; fi
echo MANAGER_DOCKER_READY
docker import /input/probe.tar ci222-local-probe >/run/import.log
docker run --rm --pull=never --network none --memory 64m --cpus 0.5 --pids-limit 32 \
  --cap-drop ALL --security-opt no-new-privileges ci222-local-probe \
  /bin/busybox sh -ec 'test "$(/bin/busybox cat /sys/fs/cgroup/memory.max)" = 67108864; test "$(/bin/busybox cat /sys/fs/cgroup/cpu.max)" = "50000 100000"; test "$(/bin/busybox cat /sys/fs/cgroup/pids.max)" = 32'
echo MANAGER_CONTAINER_EXECUTED
for source in "$HOST_CANARY" "$HOST_SOCKET"; do
  if docker run --rm --pull=never --network none --mount "type=bind,src=$source,dst=/canary,readonly" \
    ci222-local-probe /bin/busybox test -e /canary >/run/reject.log 2>&1; then
    echo HOST_BIND_UNEXPECTED; exit 3
  else
    reject_exit=$?
  fi
  test "$reject_exit" = 125
  /bin/busybox grep -F "bind source path does not exist: $source" /run/reject.log
done
echo HOST_FILE_SOCKET_BIND_REJECTED
docker run --rm --pull=never --network none --mount type=bind,src=/,dst=/manager,readonly \
  --env "HOST_CANARY=$HOST_CANARY" ci222-local-probe /bin/busybox sh -ec \
  'test ! -e "/manager$HOST_CANARY"; test ! -e /manager/run/user; test ! -e /manager/mnt/c'
echo MANAGER_PATH_PROBE_PASSED
docker run --rm --pull=never --privileged --pid host --network host --cgroupns host \
  --mount type=bind,src=/,dst=/manager,readonly \
  --env "HOST_CANARY=$HOST_CANARY" --env "HOST_SOCKET=$HOST_SOCKET" \
  --env "HOST_PID_NAMESPACE=$HOST_PID_NAMESPACE" --env "HOST_NET_NAMESPACE=$HOST_NET_NAMESPACE" \
  --env "HOST_CGROUP_NAMESPACE=$HOST_CGROUP_NAMESPACE" \
  ci222-local-probe /bin/busybox sh -ec '
    test "$(/bin/busybox readlink /proc/self/ns/pid)" != "$HOST_PID_NAMESPACE"
    test "$(/bin/busybox readlink /proc/self/ns/net)" != "$HOST_NET_NAMESPACE"
    test "$(/bin/busybox readlink /proc/self/ns/cgroup)" != "$HOST_CGROUP_NAMESPACE"
    read inner_uid outer_uid count < /proc/self/uid_map
    test "$outer_uid" -gt 0
    test ! -e "/proc/1/root$HOST_CANARY"
    test ! -e "/manager$HOST_SOCKET"
    test ! -e /manager/run/user
    test ! -e /manager/var/run/docker.sock
    test ! -e /manager/mnt/c
    ipv4_routes=$(/bin/busybox ip route show)
    ipv6_routes=$(/bin/busybox ip -6 route show)
    while read destination rest; do
      case "$destination" in default|0.0.0.0/0|::/0) exit 1 ;; esac
    done <<ROUTES
$ipv4_routes
$ipv6_routes
ROUTES
  '
echo PRIVILEGED_HOST_NAMESPACE_PROBE_PASSED
test -z "$(docker ps -aq)"
echo MANAGER_PROBE_END
'''


def execute(args, **kwargs):
    return subprocess.run(args, check=True, timeout=120, **kwargs)


def copy_binary(source, root):
    source = Path(source)
    target = root / str(source).lstrip('/')
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source.resolve(), target)
    target.chmod(0o755)
    result = subprocess.run(['ldd', str(source)], capture_output=True, text=True)
    for name in re.findall(r'(/[^\s()]+)', result.stdout):
        path = Path(name)
        if path.is_file():
            dest = root / name.lstrip('/')
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path.resolve(), dest)
            dest.chmod(0o755)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--confirm-local-experiment', action='store_true')
    parser.add_argument('--diagnose-cgroup-only', action='store_true')
    parser.add_argument('--backend-bundle')
    parser.add_argument('--stop-fixture', choices=['cancel', 'timeout'])
    parser.add_argument('--evidence-output')
    args = parser.parse_args()
    diagnose_only = args.diagnose_cgroup_only
    backend_bundle = Path(args.backend_bundle).resolve(strict=True) if args.backend_bundle else None
    stop_fixture = args.stop_fixture
    evidence_output = Path(args.evidence_output).absolute() if args.evidence_output else None
    if evidence_output and not backend_bundle:
        parser.error('Evidence output requires backend bundle')
    if stop_fixture and (backend_bundle or diagnose_only):
        parser.error('Stop fixture must run independently of backend and diagnostics')
    if not args.confirm_local_experiment:
        print('PLAN ONLY: incomplete local manager experiment; not a server installer or isolation approval')
        return
    if os.geteuid() != 0 or 'microsoft' not in Path('/proc/sys/kernel/osrelease').read_text().lower():
        raise RuntimeError('Local root WSL fixture only')
    backend = None
    if backend_bundle:
        if diagnose_only: raise ValueError('Diagnostic mode cannot run backend')
        spec = importlib.util.spec_from_file_location('manager_backend', Path(__file__).with_name('ci-local-backend-runtime.py'))
        backend = importlib.util.module_from_spec(spec); spec.loader.exec_module(backend)
        backend.inputs.inspect_bundle(backend_bundle, backend.MANIFEST_SHA, backend.JDK_SHA)
    base = Path(tempfile.mkdtemp(prefix='ci222-manager-'))
    print('MANAGER_EVIDENCE_DIR=' + str(base), flush=True)
    package = base / 'package'
    package.mkdir()
    lists = base / 'apt-lists'
    (lists / 'partial').mkdir(parents=True)
    apt = ['apt-get', '-o', 'Dir::State::lists=' + str(lists),
           '-o', 'APT::Get::List-Cleanup=0']
    execute(apt + ['update'], stdout=subprocess.DEVNULL)
    execute(apt + ['download', 'systemd-container', 'systemd'], cwd=package)
    debs = list(package.glob('*.deb'))
    if len(debs) != 2:
        raise RuntimeError('Expected manager and matching library packages')
    for deb in debs:
        execute(['dpkg-deb', '-x', str(deb), str(package / 'extracted')])
    nspawn = package / 'extracted/usr/bin/systemd-nspawn'
    shared = list((package / 'extracted').rglob('libsystemd-shared-*.so'))
    if len(shared) != 1:
        raise RuntimeError('Expected one matching systemd shared library')
    library_path = str(shared[0].parent)
    execute([str(nspawn), '--version'], env={'LD_LIBRARY_PATH': library_path})
    archive = base / 'docker.tgz'
    execute(['curl', '--fail', '--silent', '--show-error', '--proto', '=https', '--max-time', '90',
        'https://download.docker.com/linux/static/stable/x86_64/docker-29.7.2.tgz', '-o', str(archive)])
    if hashlib.sha256(archive.read_bytes()).hexdigest() != '803d433f226db4776e1768fd319fc6c6e4935a456acf84fcc0080818b854bc8f':
        raise RuntimeError('Static Docker hash changed')
    root = base / 'root'
    for name in ('bin', 'usr/bin', 'etc', 'run', 'tmp', 'var', 'data', 'input', 'dev', 'proc', 'sys'):
        (root / name).mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as stream:
        for member in stream:
            if member.isfile():
                if not re.fullmatch(r'docker/[a-zA-Z0-9-]+', member.name):
                    raise RuntimeError('Unexpected binary path')
                target = root / 'bin' / Path(member.name).name
                with stream.extractfile(member) as source, target.open('xb') as output:
                    shutil.copyfileobj(source, output)
                target.chmod(0o755)
    shutil.copyfile('/usr/bin/busybox', root / 'bin/busybox')
    (root / 'bin/busybox').chmod(0o755)
    for name in ('sh', 'mkdir', 'seq', 'sleep', 'tail', 'cat', 'mount', 'umount', 'ip', 'modprobe'):
        (root / 'bin' / name).symlink_to('busybox')
    for name in ('/usr/sbin/iptables', '/usr/sbin/ip6tables'):
        copy_binary(name, root)
    extensions = Path('/usr/lib/x86_64-linux-gnu/xtables')
    for source in extensions.glob('*.so'):
        copy_binary(source, root)
    (root / 'etc/os-release').write_text('ID=ci222-fixture\nNAME=ci222-fixture\n')
    (root / 'etc/passwd').write_text('root:x:0:0:root:/root:/bin/sh\n')
    (root / 'etc/group').write_text('root:x:0:\ndocker:x:999:\n')
    (root / 'probe.sh').write_text(SCRIPT)
    (root / 'probe.sh').chmod(0o755)
    if backend:
        backend.prepare(backend_bundle, root, copy_binary)
        (root / 'probe.sh').write_text(SCRIPT + '\n/bin/sh /backend-check.sh\n')
    if stop_fixture:
        (root / 'probe.sh').write_text(SCRIPT + '''
docker run -d --pull=never --network none --memory 64m --cpus 0.5 --pids-limit 32 \\
  --name ci222-lifecycle ci222-local-probe /bin/busybox sleep 120
echo READY > /lifecycle-ready
sleep 120
''')
    with tarfile.open(root / 'input/probe.tar', 'w') as stream:
        stream.add('/usr/bin/busybox', arcname='bin/busybox')
    canary = base / 'host-canary'
    canary.write_text('synthetic host canary')
    host_socket = base / 'host-socket'
    listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    listener.bind(str(host_socket))
    listener.listen(1)
    env = {'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LANG': 'C',
           'SYSTEMD_NSPAWN_UNIFIED_HIERARCHY': '1'}
    args = [str(nspawn), '--quiet', '--register=no', '--keep-unit', '--settings=no',
            '--private-users=pick', '--private-users-chown', '--private-network',
            '--system-call-filter=keyctl',
            '--capability=CAP_NET_ADMIN', '--directory=' + str(root),
            '--setenv=HOST_CANARY=' + str(canary),
            '--setenv=HOST_SOCKET=' + str(host_socket),
            '--setenv=HOST_PID_NAMESPACE=' + os.readlink('/proc/self/ns/pid'),
            '--setenv=HOST_NET_NAMESPACE=' + os.readlink('/proc/self/ns/net'),
            '--setenv=HOST_CGROUP_NAMESPACE=' + os.readlink('/proc/self/ns/cgroup'),
            '--setenv=DIAGNOSE_ONLY=' + ('1' if args.diagnose_cgroup_only else '0'),
            '/bin/sh', '/probe.sh']
    args = ['systemd-run', '--scope', '--quiet', '--property=Delegate=yes',
            '--property=MemoryMax=' + ('6G' if backend else '2G'),
            '--property=TasksMax=' + ('2048' if backend else '512'), '--property=CPUQuota=200%',
            '--unit=ci222-local-' + base.name, '--', '/usr/bin/env',
            'LD_LIBRARY_PATH=' + library_path, *args]
    try:
        if stop_fixture:
            child = subprocess.Popen(args, env=env, stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE, text=True)
            deadline = time.monotonic() + 90
            while not (root / 'lifecycle-ready').exists():
                if child.poll() is not None or time.monotonic() >= deadline:
                    raise RuntimeError('Lifecycle fixture did not become ready')
                time.sleep(0.1)
            print('LIFECYCLE_READY: running container', flush=True)
            if stop_fixture == 'timeout':
                try:
                    child.communicate(timeout=1)
                except subprocess.TimeoutExpired:
                    print('EXPECTED_TIMEOUT', flush=True)
                else:
                    raise RuntimeError('Expected a running workload timeout')
            subprocess.run(['systemctl', 'stop', 'ci222-local-' + base.name + '.scope'], check=True, timeout=30)
            stdout, stderr = child.communicate(timeout=10)
            result = subprocess.CompletedProcess(args, child.returncode, stdout, stderr)
        elif diagnose_only:
            print('HOST_CGROUP_NAMESPACE=' + os.readlink('/proc/self/ns/cgroup'), flush=True)
            child = subprocess.Popen(args, env=env, stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE, text=True)
            time.sleep(2)
            scope = subprocess.run(['systemctl', 'show', 'ci222-local-' + base.name + '.scope',
                                    '-p', 'ControlGroup', '--value'], capture_output=True, text=True)
            scope_path = scope.stdout.strip()
            expected = '/system.slice/ci222-local-' + base.name + '.scope'
            print('HOST_SCOPE=' + scope_path, flush=True)
            if scope_path == expected:
                parent = Path('/sys/fs/cgroup' + scope_path)
                for procs in parent.rglob('cgroup.procs'):
                    for pid in procs.read_text().split():
                        try:
                            proc = Path('/proc') / pid
                            print(json.dumps({'pid': pid,
                                'group': str(procs.parent.relative_to(parent)),
                                'cgroup_ns': os.readlink(proc / 'ns/cgroup'),
                                'uid_map': (proc / 'uid_map').read_text().strip()}), flush=True)
                        except FileNotFoundError:
                            pass
            stdout, stderr = child.communicate(timeout=90)
            result = subprocess.CompletedProcess(args, child.returncode, stdout, stderr)
        else:
            result = subprocess.run(args, env=env, capture_output=True, text=True, timeout=1900 if backend else 90)
    except subprocess.TimeoutExpired as exc:
        result = subprocess.CompletedProcess(args, 124,
            exc.stdout.decode(errors='replace') if isinstance(exc.stdout, bytes) else (exc.stdout or ''),
            exc.stderr.decode(errors='replace') if isinstance(exc.stderr, bytes) else (exc.stderr or ''))
    finally:
        subprocess.run(['systemctl', 'stop', 'ci222-local-' + base.name + '.scope'],
                       capture_output=True, timeout=30)
        listener.close()
    if canary.read_text() != 'synthetic host canary':
        raise RuntimeError('Host canary changed')
    scope_state = subprocess.run(['systemctl', 'is-active',
        'ci222-local-' + base.name + '.scope'], capture_output=True, text=True, timeout=10)
    if scope_state.stdout.strip() not in ('inactive', 'unknown'):
        raise RuntimeError('Local scope cleanup not verified')
    print('LOCAL_SCOPE_STOPPED; HOST_CANARY_UNCHANGED', flush=True)
    if backend:
        print('\n'.join((result.stdout + result.stderr).splitlines()[-90:]))
    else:
        print(result.stdout, end='')
        print(result.stderr, end='')
    (base / 'result.json').write_text(json.dumps({'exit': result.returncode,
        'stdout': result.stdout, 'stderr': result.stderr}, indent=2))
    print('MANAGER_CHECK_EXIT=' + str(result.returncode), flush=True)
    if stop_fixture:
        scope_path = Path('/sys/fs/cgroup/system.slice/ci222-local-' + base.name + '.scope')
        if scope_path.exists(): raise RuntimeError('Stopped scope cgroup still exists')
        print('LIFECYCLE_' + stop_fixture.upper() + ': PASS; scope/cgroup removed', flush=True)
        return
    if backend:
        passed = backend.report(root, result.returncode)
        if evidence_output:
            backend.persist_evidence(root, base, evidence_output)
        if not passed: raise SystemExit(2)
    raise SystemExit(result.returncode)


if __name__ == '__main__':
    main()
