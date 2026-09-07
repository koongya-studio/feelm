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
FILES = (
    'ci-job-safety.py', 'ci-bounded-output.py',
    'ci-job-io.py', 'ci-job-lifecycle.py',
    'ci-gitlab-job-adapter.py', 'ci-gitlab-custom-executor.py', 'ci-gitlab-server-install.py',
    'ci-server-manager-pilot.py', 'ci-server-sysfs-bootstrap.py', 'ci-local-manager-check.py', 'ci-local-backend-runtime.py',
    'ci-local-backend-inputs.py', 'ci-local-backend-transfer.py', 'ci-backend-offline-prepare.py',
    'ci-rootless-backend-check.py', 'ci-rootless-network-check.py',
    'rootless-backend/LoopbackContainers.java', 'rootless-backend/pilot.init.gradle',
    'rootless-backend/META-INF/services/org.testcontainers.core.CreateContainerCmdModifier',
    'systemd/feelm-ci222-gitlab-runner.service',
)
SOURCE_SHA = '3e151aef092a1db51f1295f6f9c27e7a68141a7ced838503e67b1eaf21e82ceb'
REVISION = '0e2bab04ef2fa7601747f4718d239eaabdc79df8'


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
