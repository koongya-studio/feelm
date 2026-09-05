#!/usr/bin/env python3
"""Prepare official static tools in local WSL /tmp; never install/start a service."""
import hashlib
import argparse
from pathlib import Path, PurePosixPath
import subprocess
import tarfile
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--probe-rootfs', action='store_true')
    args = parser.parse_args()
    if 'microsoft' not in Path('/proc/sys/kernel/osrelease').read_text().lower():
        raise RuntimeError('Local WSL only')
    directory = Path(tempfile.mkdtemp(prefix='ci222-docker-tools-'))
    print('TOOL_DIR=' + str(directory), flush=True)
    archive = directory / 'docker.tgz'
    subprocess.run(['curl', '--fail', '--silent', '--show-error', '--proto', '=https',
                    '--tlsv1.2', '--max-time', '90',
                    'https://download.docker.com/linux/static/stable/x86_64/docker-29.7.2.tgz',
                    '-o', str(archive)], check=True)
    with tarfile.open(archive) as stream:
        entries = stream.getmembers()
        for member in entries:
            name = PurePosixPath(member.name)
            if (name.is_absolute() or '..' in name.parts or name.parts[0] != 'docker'
                    or not (member.isfile() or member.isdir())):
                raise RuntimeError('Unexpected archive member')
            # Do not retain the build machine's UID/GID from the official tarball.
            member.uid = member.gid = 0
            member.uname = member.gname = 'root'
            member.mode = 0o755
        stream.extractall(directory, members=entries, numeric_owner=True)
    print('ARCHIVE_SHA256=' + hashlib.sha256(archive.read_bytes()).hexdigest())
    for tool in ('dockerd', 'containerd', 'runc'):
        subprocess.run([str(directory / 'docker' / tool), '--version'], check=True, timeout=10)
    print('TOOLS_PREPARED: no system service installed', flush=True)
    if args.probe_rootfs:
        result = subprocess.run(['/usr/bin/python3', '-I', '-B',
            str(Path(__file__).with_name('ci-rootfs-fixture.py')), 'run', '--confirm-local-fixture',
            '--docker-tools', str(directory / 'docker')], check=False)
        raise SystemExit(result.returncode)


if __name__ == '__main__':
    main()
