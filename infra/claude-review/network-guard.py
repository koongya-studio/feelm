#!/usr/bin/env python3
"""S15P21E106-276 리뷰 계정 보호 규칙이며 기존 MR Runner와 UFW 규칙은 변경하지 않는다."""
import argparse
import copy
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import stat
import subprocess
import sys

TABLE = 'feelm_claude_276'
ACCOUNT = 'feelm-ci-review'
MARKER = 'S15P21E106-276 review guard v1'
CONFIG = '/etc/feelm-ci-review/network.json'
NFT = '/usr/sbin/nft'
ENV = {'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LANG': 'C', 'LC_ALL': 'C'}


def execute(*args, input=None):
    return subprocess.run(args, input=input, text=True, capture_output=True, check=True,
                          timeout=30, env=ENV, cwd='/')


def trusted_file(value):
    path = Path(value).absolute()
    for item in [path, *path.parents]:
        info = item.lstat()
        expected = stat.S_ISREG(info.st_mode) if item == path else stat.S_ISDIR(info.st_mode)
        if not expected or info.st_uid != 0 or info.st_mode & 0o022:
            raise ValueError('Root-owned regular file and non-writable parents required')
    return path


def render(config):
    if set(config) != {'uid', 'deny_ips', 'dns_ips'}:
        raise ValueError('Unexpected configuration fields')
    if type(config['uid']) is not int or not 1 <= config['uid'] <= 4294967294:
        raise ValueError('A non-root account UID is required')
    denied = config['deny_ips']
    dns = config['dns_ips']
    if not isinstance(denied, list) or len(denied) < 2:
        raise ValueError('Both service and distributed public IPs required')
    if not isinstance(dns, list) or not dns:
        raise ValueError('Explicit DNS addresses required')
    if not all(isinstance(a, str) for a in denied + dns):
        raise ValueError('IP addresses must be strings')
    blocked = [ipaddress.ip_address(a) for a in denied]
    resolvers = [ipaddress.ip_address(a) for a in dns]
    if not all(a.version == 4 and a.is_global for a in blocked):
        raise ValueError('Distinct global IPv4 service addresses required')
    if len(set(blocked)) != len(blocked) or len(set(resolvers)) != len(resolvers):
        raise ValueError('Duplicate addresses')
    if not all(a.version == 4 and not a.is_unspecified and not a.is_multicast
               and not a.is_link_local and int(a) < int(ipaddress.ip_address('224.0.0.0'))
               for a in resolvers):
        raise ValueError('Usable IPv4 DNS addresses required')
    if set(blocked) & set(resolvers):
        raise ValueError('DNS must not overlap denied service addresses')
    lines = [f'create table inet {TABLE} {{ comment "{MARKER}"; }}',
             f'table inet {TABLE} {{',
             ' chain output { type filter hook output priority -10; policy accept;',
             f"  meta skuid {config['uid']} jump restricted", ' }', ' chain restricted {']
    for address in sorted(blocked):
        lines.append(f'  ip daddr {address} counter reject')
    for address in sorted(resolvers):
        for protocol in ('udp', 'tcp'):
            lines.append(f'  ip daddr {address} {protocol} dport 53 counter return')
    lines.extend([
        '  meta nfproto ipv6 counter reject',
        '  ip daddr { 0.0.0.0/8, 10.0.0.0/8, 100.64.0.0/10, 127.0.0.0/8, '
        '169.254.0.0/16, 172.16.0.0/12, 192.168.0.0/16, 224.0.0.0/4, 240.0.0.0/4 } counter reject',
        '  fib daddr type local counter reject',
        '  tcp dport 443 counter return', '  counter reject', ' }', '}'])
    return '\n'.join(lines) + '\n'


def policy(config_path):
    import pwd
    trusted_file(__file__)
    config = json.loads(trusted_file(config_path).read_text())
    source = render(config)
    account = pwd.getpwnam(ACCOUNT)
    if (account.pw_uid != config['uid'] or account.pw_dir != '/home/' + ACCOUNT
            or set(os.getgrouplist(ACCOUNT, account.pw_gid)) != {account.pw_gid}):
        raise ValueError('Review account UID, home or groups differ')
    return source


def canonical(payload):
    """dump metadata, 객체 handle과 익명 counter 값만 비교에서 제외한다."""
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
subprocess.run(['/usr/sbin/nft', '-j', '-n', 'list', 'table', 'inet', 'feelm_claude_276'],
               check=True, timeout=10)
'''


def reference(source):
    # 동일한 kernel/nft 버전으로 set, prefix와 암시적 reject 유형을 정규화한다.
    # 호스트 규칙은 변경하지 않으며 임시 namespace는 자식 프로세스와 함께 사라진다.
    result = execute('/usr/bin/unshare', '--net', '--', '/usr/bin/python3', '-I', '-c',
                     REFERENCE, os.readlink('/proc/self/ns/net'), input=source)
    return canonical(json.loads(result.stdout))


def exists():
    data = json.loads(execute(NFT, '-j', 'list', 'tables').stdout)
    return any(x.get('table', {}).get('family') == 'inet'
               and x.get('table', {}).get('name') == 'feelm_claude_276' for x in data['nftables'])


def verify(expected):
    actual = canonical(json.loads(execute(NFT, '-j', '-n', 'list', 'table', 'inet', 'feelm_claude_276').stdout))
    if actual != expected:
        raise ValueError('GUARD_MISMATCH: keep review workloads stopped; no automatic replacement')
    digest = hashlib.sha256(json.dumps(expected, sort_keys=True).encode()).hexdigest()
    print('GUARD_VERIFIED: ' + digest)


def ensure(source, expected):
    if not exists():
        execute(NFT, '--check', '--file', '-', input=source)
        # create table은 동시 생성을 거부하며 기존 규칙을 병합하거나 비우지 않는다.
        execute(NFT, '--file', '-', input=source)
    verify(expected)
    # 재검증에 실패해도 설치한 차단 규칙은 의도적으로 유지한다.


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['plan', 'check', 'ensure', 'verify'], nargs='?', default='plan')
    parser.add_argument('--config', default=CONFIG)
    args = parser.parse_args()
    if not hasattr(os, 'geteuid') or os.geteuid() != 0:
        parser.error('Linux root operator required')
    source = policy(args.config)
    if args.action == 'plan':
        print(source, end='')
        return
    expected = reference(source)
    if args.action == 'check':
        print('ISOLATED_NFT_CHECK: PASS (host firewall unchanged)')
    elif args.action == 'ensure':
        ensure(source, expected)
    else:
        verify(expected)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as exc:
        print('REVIEW_GUARD_FAILED: ' + type(exc).__name__, file=sys.stderr)
        sys.exit(1)
