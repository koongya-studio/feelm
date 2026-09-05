#!/usr/bin/env python3
"""Root-owned boot guard and start gate for the reviewed 222 pilot, not an MR sandbox."""
import argparse
import copy
import hashlib
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import stat
import subprocess
import sys

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
CONFIG = '/etc/feelm-ci222/guard.json'
ENV = {'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LANG': 'C', 'LC_ALL': 'C'}
NFT = '/usr/sbin/nft'


def execute(*args, input=None):
    return subprocess.run(args, input=input, text=True, capture_output=True, check=True,
                          timeout=30, env=ENV, cwd='/')


def trusted_file(path):
    path = Path(path).absolute()
    for item in [path, *path.parents]:
        info = item.lstat()
        if (stat.S_ISLNK(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022
                or not (stat.S_ISREG(info.st_mode) if item == path else stat.S_ISDIR(info.st_mode))):
            raise ValueError('Expected root-owned file and parents without group/other write access')
    return path


def policy(config_path):
    # Validate the helper before importing executable code. -I is mandatory in installed units.
    trusted_file(__file__)
    helper = trusted_file(HERE / 'ci-network-guard.py')
    config = json.loads(trusted_file(config_path).read_text())
    if set(config) != {'uid', 'deny_ips'} or type(config['uid']) is not int or config['uid'] != 1001:
        raise ValueError('This deployment requires the reviewed UID 1001')
    addresses = config['deny_ips']
    if not isinstance(addresses, list) or len(addresses) < 2 or not all(isinstance(a, str) for a in addresses):
        raise ValueError('Configure both operating public addresses; no example defaults')
    parsed = [ipaddress.ip_address(a) for a in addresses]
    if not all(a.is_global for a in parsed) or len(set(parsed)) != len(parsed):
        raise ValueError('Expected distinct public IP addresses')
    spec = importlib.util.spec_from_file_location('trusted_guard', helper)
    guard = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(guard)
    if guard.account_uid() != config['uid']:
        raise ValueError('Account UID no longer matches reviewed configuration')
    return guard, guard.rules(config['uid'], addresses)


def canonical(payload):
    """Ignore only dump metadata, object handles and anonymous counter values."""
    table, chains, rules = None, {}, {}
    for entry in payload['nftables']:
        if set(entry) == {'metainfo'}:
            continue
        if len(entry) != 1:
            raise ValueError('Unexpected nft JSON entry')
        kind, value = next(iter(entry.items()))
        value = copy.deepcopy(value)
        value.pop('handle', None)
        if kind == 'table':
            if table is not None:
                raise ValueError('Duplicate table')
            table = value
        elif kind == 'chain':
            name = value['name']
            if name in chains:
                raise ValueError('Duplicate chain')
            chains[name] = value
        elif kind == 'rule':
            for expression in value['expr']:
                counter = expression.get('counter')
                if isinstance(counter, dict) and set(counter) == {'packets', 'bytes'}:
                    expression['counter'] = {'packets': 0, 'bytes': 0}
            rules.setdefault(value['chain'], []).append(value)
        else:
            raise ValueError('Unexpected nft object; refuse automatic adoption')
    if table is None or not chains or not rules:
        raise ValueError('Incomplete guard table')
    return {'table': table, 'chains': chains, 'rules': rules}


REFERENCE = r'''
import os, subprocess, sys
if os.readlink('/proc/self/ns/net') == sys.argv[1]:
    raise RuntimeError('Reference must not run in host network namespace')
source = sys.stdin.read()
subprocess.run(['/usr/sbin/nft', '--file', '-'], input=source, text=True, check=True, timeout=10)
subprocess.run(['/usr/sbin/nft', '-j', '-n', 'list', 'table', 'inet', 'feelm_ci_222'],
               check=True, timeout=10)
'''


def reference(source):
    # Same kernel/nft version canonicalizes sets, prefixes and implicit reject types.
    # No host rules are changed; this short-lived namespace dies with the child.
    result = execute('/usr/bin/unshare', '--net', '--', '/usr/bin/python3', '-I', '-c',
                     REFERENCE, os.readlink('/proc/self/ns/net'), input=source)
    return canonical(json.loads(result.stdout))


def exists():
    data = json.loads(execute(NFT, '-j', 'list', 'tables').stdout)
    return any(x.get('table', {}).get('family') == 'inet'
               and x.get('table', {}).get('name') == 'feelm_ci_222' for x in data['nftables'])


def verify(expected):
    actual = canonical(json.loads(execute(NFT, '-j', '-n', 'list', 'table', 'inet', 'feelm_ci_222').stdout))
    if actual != expected:
        raise ValueError('GUARD_MISMATCH: keep test workloads stopped; no automatic replacement')
    digest = hashlib.sha256(json.dumps(expected, sort_keys=True).encode()).hexdigest()
    print('GUARD_VERIFIED: ' + digest)


def ensure(source, expected):
    if not exists():
        execute(NFT, '--check', '--file', '-', input=source)
        # create table rejects races with another creator, never merges/flushes.
        execute(NFT, '--file', '-', input=source)
    verify(expected)
    # A failed readback deliberately leaves the installed deny rules in place.


def resource_gate():
    names = ['CPUQuotaPerSecUSec', 'MemoryHigh', 'MemoryMax', 'TasksMax']
    data = execute('/usr/bin/systemctl', 'show', 'user-1001.slice',
                   '--property=' + ','.join(names)).stdout
    actual = dict(line.split('=', 1) for line in data.splitlines() if '=' in line)
    expected = dict(zip(names, ['2s', '5368709120', '6442450944', '2048']))
    if actual != expected:
        raise ValueError('RESOURCE_MISMATCH: reviewed test slice limits required')
    print('START_GATE: PASS; point-in-time only, not untrusted MR approval')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['verify', 'ensure', 'start-check'])
    parser.add_argument('--config', default=CONFIG)
    args = parser.parse_args()
    if os.geteuid() != 0:
        parser.error('Requires root; never grant this script as a general sudo command to test jobs')
    _, source = policy(args.config)
    expected = reference(source)
    if args.action == 'ensure':
        ensure(source, expected)
    else:
        verify(expected)
    if args.action == 'start-check':
        resource_gate()


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as exc:
        print('GUARD_SERVICE_FAILED: ' + type(exc).__name__ + ': ' + str(exc), file=sys.stderr)
        sys.exit(1)
