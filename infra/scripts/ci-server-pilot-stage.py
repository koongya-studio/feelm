#!/usr/bin/env python3
"""Upload only reviewed pilot code/source into a new root-owned server directory."""
import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import subprocess
import tarfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location(
    'ci414_runtime_contract', HERE / 'ci-backend-runtime-contract.py')
runtime = importlib.util.module_from_spec(spec); spec.loader.exec_module(runtime)
FILES = runtime.FILES + (
    'ci-gitlab-job-adapter.py', 'ci-gitlab-custom-executor.py', 'ci-gitlab-server-install.py',
    'ci-gitlab-adapter-update.py',
    'ci-frontend-mr-boundary.py', 'ci-frontend-mr-config.py', 'ci-frontend-mr-runner.py',
    'ci-pipeline-cache-policy.py', 'ci-pipeline-mr-boundary.py',
    'ci-pipeline-mr-config.py', 'ci-pipeline-mr-prepare.py', 'ci-pipeline-mr-runner.py',
    'ci-consumer-mr-boundary.py', 'ci-consumer-mr-config.py',
    'ci-consumer-mr-prepare.py', 'ci-consumer-mr-runner.py',
    'ci-infra-mr-boundary.py', 'ci-infra-mr-runner.py',
    'ci-dependency-cache.py', 'ci-dependency-cache-context.py', 'ci-dependency-cache-install.py',
    'ci-network-guard.py',
    'systemd/feelm-ci222-gitlab-runner.service',
)
SOURCE_SHA = '388ae26fd74dca6864f5fb469fdf47b2d4a34e42b4989d7f29caf36662b4f875'
REVISION = 'e5588d312bdfe058b03d15b34b1cbfa3a9935093'


def archive_bytes():
    source = subprocess.run(['git', '-C', str(HERE.parent.parent), 'archive', '--format=tar',
                             REVISION, 'backend'], capture_output=True, check=True, timeout=30).stdout
    if hashlib.sha256(source).hexdigest() != SOURCE_SHA:
        raise ValueError('Reviewed backend source hash differs')
    files = {'backend.tar': source}
    for name in FILES:
        path = HERE / name
        if path.is_symlink() or not path.is_file(): raise ValueError('Missing/linked pilot source')
        files['scripts/' + name] = path.read_bytes().replace(b'\r\n', b'\n')
    hashes = {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}
    files['upload-manifest.json'] = json.dumps(hashes, indent=2).encode()
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode='w') as archive:
        for name, data in files.items():
            item = tarfile.TarInfo(name); item.mode = 0o600; item.size = len(data)
            archive.addfile(item, io.BytesIO(data))
    return output.getvalue(), hashes


def remote_bootstrap(hashes):
    # Paths are bounded to a new private directory. No checkout/pull, existing
    # rootfs, environment file or system configuration is modified.
    return '''import hashlib, json, os, sys, tarfile, tempfile
from pathlib import Path
expected = ''' + repr(hashes) + '''
base = Path(tempfile.mkdtemp(prefix='ci222-server-', dir='/var/tmp'))
os.chmod(base, 0o700)
seen = set()
with tarfile.open(fileobj=sys.stdin.buffer, mode='r|') as archive:
    for item in archive:
        if not item.isfile() or item.name in seen or item.name not in {*expected, 'upload-manifest.json'} or item.size > 64*1024**2:
            raise ValueError('Unexpected upload entry')
        data = archive.extractfile(item).read()
        if item.name in expected and hashlib.sha256(data).hexdigest() != expected[item.name]:
            raise ValueError('Upload hash mismatch')
        if item.name == 'upload-manifest.json' and json.loads(data) != expected:
            raise ValueError('Upload manifest mismatch')
        target = base / item.name
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open('xb') as output: output.write(data)
        target.chmod(0o600)
        seen.add(item.name)
if seen != {*expected, 'upload-manifest.json'}: raise ValueError('Incomplete upload')
print('SERVER_STAGE=' + str(base))
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--confirm-server-stage', action='store_true')
    args = parser.parse_args()
    if not args.confirm_server_stage:
        print('PLAN: upload selected scripts and exact reviewed backend archive to new /var/tmp/ci222-server-*; no execution')
        return
    payload, hashes = archive_bytes()
    import base64
    encoded = base64.b64encode(remote_bootstrap(hashes).encode()).decode()
    command = "sudo -n python3 -I -B -c 'import base64; exec(base64.b64decode(\"" + encoded + "\"))'"
    result = subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
                             '-o', 'ConnectTimeout=10', 'e106', command], input=payload,
                            capture_output=True, check=True, timeout=120)
    output = result.stdout.decode().strip()
    if not re.fullmatch(r'SERVER_STAGE=/var/tmp/ci222-server-[a-z0-9_]+', output):
        raise ValueError('Unexpected stage response: ' + output)
    print(output)
    print('UPLOAD_SHA256=' + hashlib.sha256(payload).hexdigest())


if __name__ == '__main__':
    main()
