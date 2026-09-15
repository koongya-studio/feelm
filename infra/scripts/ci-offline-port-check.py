#!/usr/bin/env python3
"""Fixed offline loopback-port probe. No backend, DNS, pull or Runner changes."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('network', Path(__file__).with_name('ci-rootless-network-check.py'))
network = importlib.util.module_from_spec(spec)
spec.loader.exec_module(network)
SOCKET = Path('/run/feelm-ci222-offline/docker.sock')
ROOT = '/home/feelm-ci-test/offline-docker-222/data/docker'
IMAGE = 'sha256:78387bc3881b8273120a12ebe6c1ab22b018ccc2c9adf565ae1ac9b536e184ea'
CLIENT = network.NAMESPACE_SETUP + '''
import urllib.request, time
verify_namespace_identity()
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
url = 'http://127.0.0.1:' + os.environ['PROBE_PORT']
for attempt in range(10):
    try:
        with opener.open(url, timeout=2) as response:
            if response.status != 200 or response.read(128) != os.environ['PROBE_TOKEN'].encode():
                raise RuntimeError('Unexpected response token')
        print('OFFLINE_LOOPBACK_PORT: PASS', flush=True)
        break
    except OSError:
        if attempt == 9: raise
        time.sleep(0.2)
'''


def validate_info(info):
    if info.get('DockerRootDir') != ROOT or 'name=rootless' not in info.get('SecurityOptions', []):
        raise ValueError('Wrong Docker target; no fallback')


def prefix():
    import pwd
    if os.geteuid() != 1001 or pwd.getpwuid(1001).pw_name != 'feelm-ci-test':
        raise ValueError('Run as feelm-ci-test, never sudo root')
    if set(os.getgroups()) - {1001}:
        raise ValueError('Unexpected supplementary groups')
    if os.access('/var/run/docker.sock', os.R_OK) or os.access('/var/run/docker.sock', os.W_OK):
        raise ValueError('Operating socket accessible')
    if SOCKET.is_symlink() or not SOCKET.is_socket() or SOCKET.stat().st_uid != 1001:
        raise ValueError('Unexpected offline socket')
    return ['/usr/bin/docker', '--host', 'unix://' + str(SOCKET)]


def run():
    docker = prefix()
    validate_info(json.loads(network.execute(docker, 'info', '--format', '{{json .}}').stdout))
    if network.execute(docker, 'ps', '-aq').stdout.strip():
        raise ValueError('Offline Docker must have no containers before probe')
    network.execute(docker, 'image', 'inspect', IMAGE)
    created, code = [], 2
    token = uuid.uuid4().hex
    # Pin FDs before creating resources. Only the fixed offline pidfile is accepted.
    with network.namespace_target(offline=True) as target:
        command, fds, identities, uid = target
        try:
            net = network.execute(docker, 'network', 'create', 'ci222-port-' + token).stdout.strip()
            created.append(('network', net))
            server = network.execute(docker, 'create', '--pull=never', '--network', net,
                                     *network.limits(), '--publish', '127.0.0.1::8000',
                                     '--env', 'PROBE_TOKEN=' + token,
                                     IMAGE, 'python', '-B', '-c', network.SERVER).stdout.strip()
            created.append(('container', server))
            network.execute(docker, 'start', server)
            route = network.route_details(
                json.loads(network.execute(docker, 'inspect', server).stdout)[0],
                json.loads(network.execute(docker, 'network', 'inspect', net).stdout)[0])
            env = {'PATH': '/usr/bin:/bin', 'PROBE_TOKEN': token,
                   'PROBE_PORT': str(route['published_host_port']), 'PROBE_OUTER_UID': str(uid)}
            env.update({'PROBE_NS_' + k: str(v) for k, v in identities.items()})
            result = subprocess.run(command + ['--', '/usr/bin/python3', '-I', '-B', '-'],
                                    input=CLIENT, text=True, capture_output=True, env=env, cwd='/',
                                    pass_fds=tuple(fds.values()), timeout=30, check=False)
            print(result.stdout, end='')
            if result.stderr:
                print(result.stderr, file=sys.stderr, end='')
            code = 0 if result.returncode == 0 else 2
        finally:
            if network.cleanup(docker, created):
                print('PROBE_CLEANUP: PASS')
            else:
                code = 3
    return code


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['plan', 'run'], nargs='?', default='plan')
    parser.add_argument('--confirm', action='store_true')
    args = parser.parse_args()
    if args.action == 'plan':
        print('PLAN: offline socket only; verified namespace FDs; one temporary server/network; '
              'loopback token check; cleanup; no DNS or backend execution')
        return 0
    if not args.confirm:
        parser.error('run requires --confirm')
    code = run()
    print(f'OFFLINE_PORT_EXIT={code}; NOT_RUNNER_ISOLATION_APPROVAL')
    return code


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, OSError, KeyError, subprocess.SubprocessError) as exc:
        print('OFFLINE_PORT_ERROR: ' + type(exc).__name__, file=sys.stderr)
        sys.exit(2)
