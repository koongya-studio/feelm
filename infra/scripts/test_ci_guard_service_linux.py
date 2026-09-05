"""Opt-in real nft regression, confined to a disposable network namespace."""
import os
from pathlib import Path
import subprocess
import sys
import unittest

CHILD = r'''
import importlib.util, json, os, subprocess, sys
from pathlib import Path
assert os.readlink('/proc/self/ns/net') != sys.argv[1], 'Refusing host network namespace'
base = Path(sys.argv[2])
def load(name, file):
    spec = importlib.util.spec_from_file_location(name, base / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module
service = load('service', 'ci-guard-service.py')
guard = load('guard', 'ci-network-guard.py')
source = guard.rules(1001, ['192.0.2.1', '192.0.2.2'])
expected = service.reference(source)
service.execute(service.NFT, 'add', 'table', 'inet', 'unrelated_test_sentinel')
def sentinel():
    return service.execute(service.NFT, '-j', 'list', 'table', 'inet', 'unrelated_test_sentinel').stdout
before = sentinel()
service.ensure(source, expected)
service.ensure(source, expected)
print('CREATE_AND_EXISTING_MATCH: PASS')
service.execute(service.NFT, 'add', 'rule', 'inet', 'feelm_ci_222', 'restricted', 'accept')
try:
    service.ensure(source, expected)
except ValueError:
    print('EXTRA_RULE_REJECTED: PASS')
else:
    raise AssertionError('Modified table adopted')
assert sentinel() == before
service.execute(service.NFT, 'delete', 'table', 'inet', 'feelm_ci_222')
try:
    service.verify(expected)
except subprocess.CalledProcessError:
    print('MISSING_TABLE_REJECTED: PASS')
else:
    raise AssertionError('Missing guard passed')
assert not service.exists(), 'Verification unexpectedly installed rules'
assert sentinel() == before
print('UNRELATED_TABLE_UNCHANGED: PASS')
'''


@unittest.skipUnless(sys.platform == 'linux' and os.environ.get('CI222_LINUX_NETNS_TEST') == '1',
                     'Opt-in Linux root netns test; no host nft changes')
class LinuxGuardTests(unittest.TestCase):
    def test_real_nft_adoption_and_rejection_in_disposable_namespace(self):
        self.assertEqual(os.geteuid(), 0)
        host = os.readlink('/proc/self/ns/net')
        result = subprocess.run(['/usr/bin/unshare', '--net', '--', '/usr/bin/python3', '-I', '-B',
                                 '-c', CHILD, host, str(Path(__file__).resolve().parent)],
                                capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('UNRELATED_TABLE_UNCHANGED: PASS', result.stdout)
        print(result.stdout)


if __name__ == '__main__':
    unittest.main()
