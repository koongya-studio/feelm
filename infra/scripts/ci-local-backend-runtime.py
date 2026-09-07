"""Offline backend runtime payload for the isolated namespace manager."""
import importlib.util
import json
import posixpath
import re
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('runtime_inputs', HERE / 'ci-local-backend-inputs.py')
inputs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inputs)
MANIFEST_SHA = '126fcba198a358f22c7108c652cfaaa44ff7f32eb4b9d36a3767ec91953b927e'
JDK_SHA = 'ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94'

PAYLOAD = r'''#!/bin/sh
set -eu
export PATH=/opt/java/bin:/bin:/usr/bin:/sbin:/usr/sbin
export JAVA_HOME=/opt/java HOME=/work/home GRADLE_USER_HOME=/work/home/.gradle
export DOCKER_HOST=unix:///run/probe-docker.sock DOCKER_CONFIG=/work/home/.docker
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/run/probe-docker.sock
export TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1
export TESTCONTAINERS_RYUK_DISABLED=false TESTCONTAINERS_RYUK_CONTAINER_PRIVILEGED=false
export TESTCONTAINERS_REUSE_ENABLE=false CI222_SUPPORT=/work/support CI222_BACKEND=/work/backend
export JAVA_TOOL_OPTIONS='-Duser.home=/work/home -XX:ActiveProcessorCount=2'
mkdir -p /work/home/.docker
java -version
javac -version
docker load --input /input/backend-images.tar
while read tag expected; do
  actual=$(docker image inspect "$tag" --format '{{.Id}}')
  test "$actual" = "$expected"
done < /input/image-ids
echo BACKEND_IMAGE_IDS_MATCHED
cd /work/backend
code=0
/bin/sh ./gradlew clean test spotlessCheck --offline --no-daemon --max-workers=2 \
  --console=plain --init-script /work/support/pilot.init.gradle || code=$?
echo "$code" > /work/gradle-exit
echo GRADLE_EXIT=$code
for i in $(seq 1 30); do
  remaining=$(docker ps -aq)
  test -z "$remaining" && break
  sleep 1
done
printf '%s' "$remaining" > /work/remaining-containers
test -z "$remaining"
exit "$code"
'''


def extract_jdk(archive_path, destination):
    # Fixed verified official archive. Only license links to regular files in
    # this distribution are materialized; no symlinks are created in the rootfs.
    with tarfile.open(archive_path, 'r:gz') as archive:
        members = archive.getmembers()
        names = set()
        total = 0
        by_name = {item.name.rstrip('/'): item for item in members}
        for item in members:
            path = PurePosixPath(item.name)
            total += item.size
            if (path.is_absolute() or '..' in path.parts or '\\' in item.name
                    or not path.parts or path.parts[0] != 'jdk-21.0.12.1+1'
                    or not (item.isdir() or item.isfile() or item.issym()) or item.name in names
                    or total > 1024**3 or len(names) >= 10000):
                raise ValueError('Unexpected JDK archive member')
            names.add(item.name)
            if item.issym():
                target_name = posixpath.normpath(posixpath.join(str(path.parent), item.linkname))
                target = by_name.get(target_name)
                if (not str(path).startswith('jdk-21.0.12.1+1/legal/')
                        or item.linkname.startswith('/') or '\\' in item.linkname
                        or not target_name.startswith('jdk-21.0.12.1+1/legal/')
                        or target is None or not target.isfile()):
                    raise ValueError('Unexpected JDK symbolic link')
        for item in members:
            if item.issym():
                continue
            parts = PurePosixPath(item.name).parts[1:]
            target = destination.joinpath(*parts)
            if item.isdir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.extractfile(item) as stream, target.open('xb') as output:
                    shutil.copyfileobj(stream, output)
                target.chmod(0o755 if item.mode & 0o111 else 0o644)
        # Materialize license links from already extracted regular files rather
        # than repeatedly seeking backwards through a large gzip stream.
        for item in members:
            if item.issym():
                path = PurePosixPath(item.name)
                source_name = posixpath.normpath(posixpath.join(str(path.parent), item.linkname))
                source = destination.joinpath(*PurePosixPath(source_name).parts[1:])
                target = destination.joinpath(*path.parts[1:])
                target.parent.mkdir(parents=True, exist_ok=True)
                with source.open('rb') as stream, target.open('xb') as output:
                    shutil.copyfileobj(stream, output)
                target.chmod(0o644)


def prepare(bundle, root, copy_binary, source_installer=None, source_payload=None, source_sha=None,
            manifest_sha=MANIFEST_SHA):
    audit = inputs.inspect_bundle(bundle, manifest_sha, JDK_SHA)
    work = root / 'work'; work.mkdir()
    if source_payload is not None and (source_installer is None or source_sha is None):
        raise ValueError('Dynamic source requires an installer and trusted digest')
    if source_installer is None:
        inputs.prep.pilot.extract_snapshot((bundle / 'backend.tar').read_bytes(), work)
    else:
        work.chmod(0o700)
        payload = source_payload if source_payload is not None else (bundle / 'backend.tar').read_bytes()
        digest = source_sha if source_sha is not None else inputs.prep.ARCHIVE_SHA
        source_installer(payload, digest, work)
    # Rebuild path-sensitive derived Gradle workspaces inside the new rootfs.
    # Keep the transferred bundle intact and copy only dependency/distribution data.
    for selected in ('caches/modules-2', 'wrapper/dists'):
        shutil.copytree(bundle / 'gradle-cache' / selected, work / 'home/.gradle' / selected)
    for path in (work / 'home/.gradle/wrapper/dists').rglob('bin/gradle'):
        path.chmod(0o755)
    for name in inputs.prep.pilot.SUPPORT_FILES:
        target = work / 'support' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(HERE / 'rootless-backend' / name, target)
    extract_jdk(bundle / 'jdk.tar.gz', root / 'opt/java')
    # Resolve native libraries of the reviewed JDK, without copying host home/config.
    for binary in [root / 'opt/java/bin/java', root / 'opt/java/bin/javac',
                   *(root / 'opt/java/lib').rglob('*.so')]:
        result = subprocess.run(['ldd', str(binary)], capture_output=True, text=True, timeout=10)
        for name in re.findall(r'(/[^\s()]+)', result.stdout):
            path = Path(name)
            if path.is_file() and not path.is_relative_to(root):
                copy_binary(str(path), root)
    for name in ('uname', 'dirname', 'basename', 'sed', 'tr', 'xargs', 'find', 'expr', 'cut', 'readlink', 'env'):
        path = root / 'bin' / name
        if not path.exists(): path.symlink_to('busybox')
    shutil.copyfile(bundle / 'backend-images.tar', root / 'input/backend-images.tar')
    manifest = json.loads((bundle / 'cache-manifest.json').read_text())
    entries = []
    for tag, expected in inputs.prep.IMAGES.items():
        entry = manifest['images'][tag]
        if entry['digest'] != expected or not re.fullmatch('sha256:[0-9a-f]{64}', entry['id']):
            raise ValueError('Unexpected reviewed image identity')
        entries.append(tag + ' ' + entry['id'])
    (root / 'input/image-ids').write_text('\n'.join(entries) + '\n')
    (root / 'backend-check.sh').write_text(PAYLOAD)
    print('BACKEND_INPUTS_COPIED: ' + json.dumps(audit), flush=True)


def report(root, process_exit):
    work = root / 'work'
    try:
        gradle_exit = int((work / 'gradle-exit').read_text().strip())
        remaining = (work / 'remaining-containers').read_text().split()
        passed = inputs.prep.pilot.report_summary(work / 'backend')
        files = list((work / 'backend/build/test-results/test').glob('TEST-*.xml'))
        suites = [ET.parse(path).getroot() for path in files]
        count = sum(int(s.get('tests', '0')) for s in suites)
        skipped = sum(int(s.get('skipped', '0')) for s in suites)
        success = process_exit == 0 and gradle_exit == 0 and passed and count == 82 and skipped == 0 and not remaining
        result = {'gradle_exit': gradle_exit, 'tests': count, 'skipped': skipped,
                  'reports_pass': passed, 'remaining_containers': remaining, 'passed': success}
    except (OSError, ValueError, ET.ParseError) as exc:
        result = {'passed': False, 'error': str(exc)}
    (work / 'backend-result.json').write_text(json.dumps(result, indent=2))
    print('LOCAL_BACKEND_RESULT: ' + json.dumps(result), flush=True)
    return result['passed']


def persist_evidence(root, base, destination):
    destination.mkdir(exist_ok=False)
    sources = [base / 'result.json', root / 'work/backend-result.json']
    sources += sorted((root / 'work/backend/build/test-results/test').glob('TEST-*.xml'))
    if len(sources) > 258: raise ValueError('Too many report files')
    total = 0
    for source in sources:
        if source.is_symlink() or not source.is_file() or not source.resolve().is_relative_to(base):
            raise ValueError('Unsafe report path')
        total += source.stat().st_size
        if source.stat().st_size > 4 * 1024**2 or total > 16 * 1024**2:
            raise ValueError('Report export exceeds limit')
        with source.open('rb') as stream, (destination / source.name).open('xb') as output:
            shutil.copyfileobj(stream, output)
    print('PERSISTED_EVIDENCE=' + str(destination), flush=True)
