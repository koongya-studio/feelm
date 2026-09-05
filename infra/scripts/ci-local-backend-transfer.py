#!/usr/bin/env python3
"""Read selected reviewed test inputs over existing SSH; no remote writes."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import subprocess
import sys
import tarfile
import tempfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('transfer_inputs', HERE / 'ci-local-backend-inputs.py')
inputs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inputs)
JDK_SHA = 'ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94'
JDK_URL = ('https://github.com/adoptium/temurin21-binaries/releases/download/'
           'jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz')

# Fixed paths only. Remote stdout is an archive; stderr carries a manifest hash.
# No remote temporary files, permissions changes, Docker API or commands from inputs.
REMOTE = r'''
import hashlib, io, json, os, stat, sys, tarfile
from pathlib import Path, PurePosixPath
cache = Path('/home/feelm-ci-test/backend-checks/ee737a8331104a619b767a40ae996129/home/.gradle')
manifest = Path('/home/feelm-ci-test/offline-inputs/9627ce2a9b774f11a71743bcfb8aec93/manifest.json')
image = Path('/home/feelm-ci-test/ci222-backend-images-E4nFHV/backend-images.tar')
def open_regular(path):
    for part in (path, *path.parents):
        if part.is_symlink(): raise ValueError('Linked source refused')
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    if not stat.S_ISREG(os.fstat(fd).st_mode):
        os.close(fd); raise ValueError('Non-regular source')
    return os.fdopen(fd, 'rb')
with open_regular(manifest) as stream:
    raw = stream.read(32 * 1024**2 + 1)
if len(raw) > 32 * 1024**2: raise ValueError('Manifest too large')
data = json.loads(raw)
if data.get('revision') != '9631afd9c616e047abbdd0ad7bf8192231f7f35c' or data.get('archive_sha256') != 'b045a76f85d8373dec543a58ec1a72e8aacebe288d0856bbfe678db8d5c4d3b1':
    raise ValueError('Wrong source manifest')
entries = data['cache']
if not isinstance(entries, dict) or not 0 < len(entries) <= 100000: raise ValueError('Cache count')
total = 0
for name, metadata in entries.items():
    path = PurePosixPath(name)
    if (path.is_absolute() or '..' in path.parts or '\\' in name or str(path) != name
        or not (name.startswith('caches/') or name.startswith('wrapper/dists/'))
        or name.endswith('.lock') or any(p.startswith('.env') or p in ('.ssh', '.docker') for p in path.parts)):
        raise ValueError('Unapproved cache path')
    total += metadata['size']
    if metadata['size'] < 0 or total > 2 * 1024**3: raise ValueError('Cache size')
print('REMOTE_MANIFEST_SHA256=' + hashlib.sha256(raw).hexdigest(), file=sys.stderr, flush=True)
def add_bytes(archive, name, contents):
    info = tarfile.TarInfo(name); info.size = len(contents); info.mode = 0o600
    archive.addfile(info, io.BytesIO(contents))
def add_file(archive, name, path, expected_sha, maximum):
    with open_regular(path) as stream:
        size = os.fstat(stream.fileno()).st_size
        if size < 0 or size > maximum: raise ValueError('File size')
        digest = hashlib.sha256()
        while chunk := stream.read(1024*1024): digest.update(chunk)
        if digest.hexdigest() != expected_sha: raise ValueError('Source hash mismatch: ' + name)
        stream.seek(0)
        info = tarfile.TarInfo(name); info.size = size; info.mode = 0o600
        archive.addfile(info, stream)
with tarfile.open(fileobj=sys.stdout.buffer, mode='w|') as archive:
    add_bytes(archive, 'cache-manifest.json', raw)
    for name, metadata in sorted(entries.items()):
        add_file(archive, 'gradle-cache/' + name, cache / name, metadata['sha256'], metadata['size'])
    add_file(archive, 'backend-images.tar', image,
        'f1fdc5c9e4e3c96d1d04f4bdab407e1953764199ad2e2a7b007c50c0810990cb', 2*1024**3)
print('REMOTE_SELECTED_EXPORT_COMPLETE', file=sys.stderr, flush=True)
'''


def safe_member(member):
    path = PurePosixPath(member.name)
    return (member.isfile() and not path.is_absolute() and '..' not in path.parts
            and '\\' not in member.name and str(path) == member.name
            and (member.name in {'cache-manifest.json', 'backend-images.tar'}
                 or member.name.startswith('gradle-cache/caches/')
                 or member.name.startswith('gradle-cache/wrapper/dists/')))


def extract_selected(archive, destination):
    seen, total = set(), 0
    with tarfile.open(archive, 'r:') as source:
        for member in source:
            total += member.size
            if not safe_member(member) or member.name in seen or total > 4 * 1024**3 or len(seen) >= 100002:
                raise ValueError('Unsafe transfer archive')
            seen.add(member.name)
            target = destination.joinpath(*PurePosixPath(member.name).parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            with source.extractfile(member) as stream, target.open('xb') as output:
                while chunk := stream.read(1024 * 1024): output.write(chunk)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--confirm-reviewed-transfer', action='store_true')
    parser.add_argument('--reuse-selected-archive')
    parser.add_argument('--cache-manifest-sha256')
    args = parser.parse_args()
    if not args.confirm_reviewed_transfer:
        print('PLAN: existing e106 SSH host key; selected cache + saved image only; official JDK download; no remote writes/tests')
        return 0
    root = Path(tempfile.mkdtemp(prefix='ci222-backend-inputs-'))
    if os.name == 'nt':
        root = Path('\\\\?\\' + str(root))
    bundle = root / 'bundle'; bundle.mkdir()
    print('TRANSFER_DIR=' + str(root), flush=True)
    if args.reuse_selected_archive:
        if not args.cache_manifest_sha256:
            parser.error('reuse requires the previously recorded remote manifest SHA256')
        archive = inputs.real_path(args.reuse_selected_archive)
        manifest_sha = [args.cache_manifest_sha256]
    else:
        archive = root / 'selected-inputs.tar'
        with archive.open('xb') as output:
            result = subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
                '-o', 'ConnectTimeout=10', 'e106', 'sudo -n python3 -I -B -'],
                input=REMOTE.encode(), stdout=output, stderr=subprocess.PIPE, timeout=600)
        stderr = result.stderr.decode(errors='replace')
        (root / 'remote-export-evidence.txt').write_text(stderr)
        print(stderr, end='', flush=True)
        if result.returncode or 'REMOTE_SELECTED_EXPORT_COMPLETE' not in stderr:
            raise ValueError('Remote transfer incomplete; retained partial archive, no extraction')
        manifest_sha = [line.split('=', 1)[1] for line in stderr.splitlines() if line.startswith('REMOTE_MANIFEST_SHA256=')]
        if len(manifest_sha) != 1: raise ValueError('Missing remote manifest fingerprint')
    extract_selected(archive, bundle)
    inputs.export_source(HERE.parent.parent, bundle / 'backend.tar')
    print('DOWNLOADING_OFFICIAL_JDK21', flush=True)
    subprocess.run(['curl.exe', '--fail', '--location', '--silent', '--show-error', '--proto', '=https',
        '--proto-redir', '=https', '--max-time', '240', '--output', str(bundle / 'jdk.tar.gz'), JDK_URL],
        check=True, timeout=250)
    audit = inputs.inspect_bundle(bundle, manifest_sha[0], JDK_SHA)
    evidence = {'manifest_sha256': manifest_sha[0], 'jdk_sha256': JDK_SHA,
                'jdk_url': JDK_URL, 'audit': audit}
    (root / 'transfer-evidence.json').write_text(json.dumps(evidence, indent=2))
    print('TRANSFER_AND_INPUT_AUDIT: ' + json.dumps(evidence), flush=True)
    print('NO_TESTS_EXECUTED; JDK_NOT_INSTALLED')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, OSError, subprocess.SubprocessError) as exc:
        print('TRANSFER_ERROR: ' + str(exc), file=sys.stderr)
        sys.exit(2)
