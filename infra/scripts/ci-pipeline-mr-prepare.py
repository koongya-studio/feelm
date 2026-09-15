#!/usr/bin/env python3
"""Prepare the pinned image, offline uv cache and config for Pipeline MR jobs."""
import argparse
import hashlib
import importlib.util
import os
from pathlib import Path
try:
    import pwd
except ImportError:  # Windows unit-test collection only; preparation is Linux-only.
    pwd = None
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import urllib.request


HERE = Path(__file__).resolve().parent
RUNNER_USER = 'feelm-ci-test'
RUNNER_UID = 1001
DOCKER_HOST = 'unix:///run/user/1001/docker.sock'
IMAGE = 'feelm-pipeline-ci:python3.10-java21-pyspark4.1.3'
BASE_IMAGE = ('apache/spark:4.1.3-scala2.13-java21-python3-ubuntu@sha256:'
              'bf9d035a7c32a8ca46aa58d6348182ffd7d2dff6409206ecfbb3915ff1fef211')
UV_VERSION = '0.12.12'
UV_ARCHIVE = 'uv-x86_64-unknown-linux-gnu.tar.gz'
UV_URL = ('https://releases.astral.sh/github/uv/releases/download/' + UV_VERSION + '/'
          + UV_ARCHIVE)
UV_SHA256 = 'ab9b309d4586403f024e100abaceb396616e178a553e2500c36087d180f09509'
NUMPY_WHEEL = 'numpy-2.2.6-cp310-cp310-manylinux_2_17_x86_64.manylinux2014_x86_64.whl'
NUMPY_URL = 'https://files.pythonhosted.org/packages/b4/63/3de6a34ad7ad6646ac7d2f55ebc6ad439dbbf9c4370017c50cf403fb19b5/' + NUMPY_WHEEL
NUMPY_SHA256 = 'fc7b73d02efb0e18c000e9ad8b83480dfcd5dfd11065997ed4c6747470ae8915'
PROJECT_SHA256 = '17b23e8435078e98cc5025bef7329f0ad7250a3bdf63caaa79906b4e0bd4a220'
LOCK_SHA256 = 'cde67015639f41a79b8400f3314ea4068d0264f77e0402385d310086eb53ab56'
CACHE_PARENT = Path('/var/lib/feelm-ci222/pipeline-cache')
MAX_DOWNLOAD = 64 * 1024**2


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


cache_policy = load('pipeline_prepare_cache_policy', 'ci-pipeline-cache-policy.py')
configurator = load('pipeline_prepare_config', 'ci-pipeline-mr-config.py')


def rootless_command(*arguments):
    return ['runuser', '--user', RUNNER_USER, '--', 'env', '-i',
            'HOME=/home/' + RUNNER_USER, 'PATH=/usr/bin:/bin',
            'DOCKER_HOST=' + DOCKER_HOST, '/usr/bin/docker', *arguments]


def checked(command, **options):
    return subprocess.run(command, check=True, timeout=options.pop('timeout', 300), **options)


def require_host():
    if (pwd is None or not sys.platform.startswith('linux') or not hasattr(os, 'geteuid')
            or os.geteuid() != 0):
        raise RuntimeError('Linux root operator required')
    account = pwd.getpwnam(RUNNER_USER)
    if account.pw_uid != RUNNER_UID or account.pw_gid != RUNNER_UID:
        raise RuntimeError('Reviewed Pipeline runner account identity differs')
    guard = subprocess.run(['systemctl', 'is-active', '--quiet', 'feelm-ci-guard.service'],
                           timeout=10)
    if guard.returncode:
        raise RuntimeError('Network guard service is not active')
    active = checked(['systemctl', 'list-units', '--state=running', '--no-legend',
                      'ci352-owner-*.service'], capture_output=True, text=True,
                     timeout=30).stdout.strip()
    if active:
        raise RuntimeError('Pipeline MR units are still running')


def trusted_source(root):
    root = Path(root).resolve(strict=True)
    values = {}
    for relative, expected in (('pipeline/spark/pyproject.toml', PROJECT_SHA256),
                               ('pipeline/spark/uv.lock', LOCK_SHA256)):
        path = root / relative
        info = path.lstat()
        if (not stat.S_ISREG(info.st_mode) or info.st_nlink != 1
                or info.st_size <= 0 or info.st_size > 16 * 1024**2):
            raise RuntimeError('Pipeline dependency input is not a bounded regular file')
        data = path.read_bytes()
        if hashlib.sha256(data).hexdigest() != expected:
            raise RuntimeError('Pipeline dependency input differs from reviewed revision')
        values[Path(relative).name] = data
    return values


def download(url, expected, destination):
    request = urllib.request.Request(url, headers={'User-Agent': 'feelm-ci352-preparer/1'})
    digest = hashlib.sha256()
    size = 0
    with urllib.request.urlopen(request, timeout=60) as response, destination.open('xb') as output:
        if response.status != 200 or response.geturl() != url:
            raise RuntimeError('Pinned build input download was redirected or rejected')
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            size += len(chunk)
            if size > MAX_DOWNLOAD:
                raise RuntimeError('Pinned build input exceeds size limit')
            digest.update(chunk)
            output.write(chunk)
    if digest.hexdigest() != expected:
        raise RuntimeError('Pinned build input digest differs')


def extract_uv(archive, destination):
    with tarfile.open(archive, mode='r:gz') as source:
        candidates = [item for item in source if item.isfile() and item.name.endswith('/uv')]
        if len(candidates) != 1 or candidates[0].size > 64 * 1024**2:
            raise RuntimeError('Pinned uv archive has an unexpected layout')
        stream = source.extractfile(candidates[0])
        if stream is None:
            raise RuntimeError('Pinned uv executable is missing')
        with destination.open('xb') as output:
            shutil.copyfileobj(stream, output)
    destination.chmod(0o755)


def dockerfile():
    return f'''FROM {BASE_IMAGE}
USER root
COPY {NUMPY_WHEEL} /tmp/{NUMPY_WHEEL}
COPY uv /usr/local/bin/uv
RUN chmod 0755 /usr/local/bin/uv \\
 && python3 -m pip install --no-cache-dir --no-index --no-deps /tmp/{NUMPY_WHEEL} \\
 && rm -f /tmp/{NUMPY_WHEEL} \\
 && python3 -c 'import numpy; assert numpy.__version__ == "2.2.6"' \\
 && uv --version | grep -E '^uv {UV_VERSION.replace('.', '[.]')}([[:space:]]|$)'
USER spark
'''


def image_identity(command):
    result = subprocess.run(command, capture_output=True, text=True, timeout=30)
    if result.returncode != 0:
        return None
    identity = result.stdout.strip()
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', identity):
        raise RuntimeError('Pipeline CI image returned an invalid identity')
    return identity


def existing_image():
    root_id = image_identity(
        ['/usr/bin/docker', 'image', 'inspect', IMAGE, '--format', '{{.Id}}'])
    rootless_id = image_identity(
        rootless_command('image', 'inspect', IMAGE, '--format', '{{.Id}}'))
    if root_id is None and rootless_id is None:
        return None
    if root_id is None or rootless_id is None or root_id != rootless_id:
        raise RuntimeError('Partial or mismatched Pipeline image installation requires review')
    return root_id


def build_image(context):
    checked(['/usr/bin/docker', 'image', 'inspect', BASE_IMAGE],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
    checked(['/usr/bin/docker', 'build', '--pull=false', '--network=none',
             '--tag', IMAGE, str(context)], timeout=900)
    root_id = checked(['/usr/bin/docker', 'image', 'inspect', IMAGE, '--format', '{{.Id}}'],
                      capture_output=True, text=True, timeout=30).stdout.strip()
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', root_id):
        raise RuntimeError('Built Pipeline image returned an invalid identity')
    saved = subprocess.Popen(['/usr/bin/docker', 'save', IMAGE], stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE)
    try:
        loaded = subprocess.run(rootless_command('load'), stdin=saved.stdout, capture_output=True,
                                timeout=600)
        if saved.stdout is not None:
            saved.stdout.close()
        _, save_error = saved.communicate(timeout=60)
    except Exception:
        saved.kill()
        saved.wait()
        raise
    if saved.returncode or loaded.returncode:
        raise RuntimeError('Pipeline image transfer to Rootless Docker failed: '
                           + save_error.decode(errors='replace')[-256:])
    rootless_id = checked(rootless_command('image', 'inspect', IMAGE, '--format', '{{.Id}}'),
                          capture_output=True, text=True, timeout=30).stdout.strip()
    if rootless_id != root_id:
        raise RuntimeError('Rootless Pipeline image identity differs after transfer')
    return rootless_id


def prepare_worktree(parent, inputs):
    directory = Path(tempfile.mkdtemp(prefix='ci352-source-', dir=parent))
    directory.chmod(0o755)
    for name, data in inputs.items():
        path = directory / name
        path.write_bytes(data)
        path.chmod(0o644)
    shutil.chown(directory, user=RUNNER_UID, group=RUNNER_UID)
    for child in directory.iterdir():
        shutil.chown(child, user=RUNNER_UID, group=RUNNER_UID)
    return directory


def uv_sync_command(source, cache, network):
    return rootless_command(
        'run', '--rm', '--pull=never', '--network=' + network, '--user', '0:0',
        '--volume', str(source) + ':/work:rw', '--volume', str(cache) + ':/cache:rw',
        '--workdir', '/work', '--env', 'HOME=/tmp', '--env', 'UV_CACHE_DIR=/cache',
        '--env', 'UV_LINK_MODE=copy', IMAGE, 'uv', 'sync', '--frozen',
        *(() if network != 'none' else ('--offline',)))


def normalize_cache(source, destination):
    source = Path(source).resolve(strict=True)
    for item in (source, *source.rglob('*')):
        info = item.lstat()
        if stat.S_ISLNK(info.st_mode):
            try:
                target = item.resolve(strict=True)
                target.relative_to(source)
            except (OSError, RuntimeError, ValueError) as exc:
                raise RuntimeError('Generated uv cache link escapes its cache root') from exc
            target_info = target.stat()
            if not (stat.S_ISDIR(target_info.st_mode) or stat.S_ISREG(target_info.st_mode)):
                raise RuntimeError('Generated uv cache link targets an unsupported entry')
        elif not (stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode)):
            raise RuntimeError('Generated uv cache contains an unsupported entry')
    # uv uses internal links to deduplicate cache entries. Materialize only
    # prevalidated in-root links so the installed cache contains no links.
    shutil.copytree(source, destination, symlinks=False)
    for item in (destination, *destination.rglob('*')):
        info = item.lstat()
        if not (stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode)):
            raise RuntimeError('Normalized uv cache contains an unsupported entry')
    for item in sorted(destination.rglob('*'), reverse=True):
        item.chmod(0o555 if item.is_dir() else 0o444)
    destination.chmod(0o555)
    digest = cache_policy.identity(destination)
    destination.chmod(0o755)
    marker = destination / cache_policy.MARKER
    marker.write_text(digest + '\n', encoding='ascii')
    marker.chmod(0o444)
    destination.chmod(0o555)
    cache_policy.verify(destination, digest)
    return digest


def prepare_cache(inputs):
    CACHE_PARENT.mkdir(parents=True, mode=0o755, exist_ok=True)
    for parent in (CACHE_PARENT.parent.parent, CACHE_PARENT.parent, CACHE_PARENT):
        info = parent.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            raise RuntimeError('Pipeline cache parent is not trusted')
    destination = CACHE_PARENT / ('uv-' + UV_VERSION + '-' + LOCK_SHA256[:12])
    if destination.exists() or destination.is_symlink():
        raise RuntimeError('Pipeline cache version already exists; refusing replacement')
    staging = Path(tempfile.mkdtemp(prefix='.ci352-cache-', dir=CACHE_PARENT))
    staging.chmod(0o700)
    shutil.chown(staging, user=RUNNER_UID, group=RUNNER_UID)
    first = prepare_worktree('/var/tmp', inputs)
    second = None
    try:
        checked(uv_sync_command(first, staging, 'bridge'), timeout=900)
        second = prepare_worktree('/var/tmp', inputs)
        checked(uv_sync_command(second, staging, 'none'), timeout=900)
        digest = normalize_cache(staging, destination)
        return destination, digest
    finally:
        for path in (first, second, staging):
            if path is not None:
                shutil.rmtree(path, ignore_errors=True)


def prepare(source_root):
    require_host()
    inputs = trusted_source(source_root)
    image_id = existing_image()
    if image_id is None:
        with tempfile.TemporaryDirectory(prefix='ci352-image-') as temporary:
            context = Path(temporary)
            uv_archive = context / UV_ARCHIVE
            download(UV_URL, UV_SHA256, uv_archive)
            download(NUMPY_URL, NUMPY_SHA256, context / NUMPY_WHEEL)
            extract_uv(uv_archive, context / 'uv')
            uv_archive.unlink()
            (context / 'Dockerfile').write_text(dockerfile(), encoding='utf-8')
            image_id = build_image(context)
    else:
        print('PIPELINE_IMAGE_REUSED=' + image_id)
    cache, digest = prepare_cache(inputs)
    configurator.install(str(cache), digest, PROJECT_SHA256, LOCK_SHA256)
    print('PIPELINE_MR_PREPARE: PASS')
    print('PIPELINE_IMAGE_ID=' + image_id)
    print('PIPELINE_CACHE=' + str(cache))
    print('PIPELINE_CACHE_SHA256=' + digest)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root')
    parser.add_argument('--confirm-prepare', action='store_true')
    args = parser.parse_args()
    if not args.confirm_prepare:
        print('PLAN: download hash-pinned uv/NumPy inputs; build a separate Pipeline image '
              'offline from the preloaded pinned Spark base; load it into Rootless Docker; '
              'prepare and recheck one offline uv cache; install the root-only config')
        return 0
    if args.source_root is None:
        parser.error('--source-root is required with --confirm-prepare')
    prepare(args.source_root)
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as exc:
        print('PIPELINE_MR_PREPARE_FAILED: ' + type(exc).__name__ + ': ' + str(exc),
              file=sys.stderr)
        raise SystemExit(2) from None
