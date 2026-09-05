#!/usr/bin/env python3
"""Stage fixed verified inputs on native WSL storage, then run the local manager."""
import argparse
import importlib.util
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('stage_transfer', HERE / 'ci-local-backend-transfer.py')
transfer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(transfer)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--confirm-local-backend', action='store_true')
    args = parser.parse_args()
    if not args.confirm_local_backend:
        print('PLAN: copy already transferred files to native WSL temp; verify and run fixed offline backend')
        return 0
    if 'microsoft' not in Path('/proc/sys/kernel/osrelease').read_text().lower():
        raise ValueError('WSL local fixture only')
    source = Path('/mnt/c/Users/SSAFY/AppData/Local/Temp/ci222-backend-inputs-qg837s4d/bundle')
    selected = Path('/mnt/c/Users/SSAFY/AppData/Local/Temp/ci222-backend-inputs-lo8i5nli/selected-inputs.tar')
    root = Path(tempfile.mkdtemp(prefix='ci222-native-inputs-'))
    print('NATIVE_INPUTS=' + str(root), flush=True)
    transfer.extract_selected(selected, root)
    for name in ('jdk.tar.gz', 'backend.tar'):
        shutil.copyfile(source / name, root / name)
    evidence_parent = Path(tempfile.mkdtemp(prefix='ci222-backend-evidence-',
        dir='/mnt/c/Users/SSAFY/AppData/Local/Temp'))
    return subprocess.call([sys.executable, '-I', '-B', str(HERE / 'ci-local-manager-check.py'),
                            '--confirm-local-experiment', '--backend-bundle', str(root),
                            '--evidence-output', str(evidence_parent / 'results')])


if __name__ == '__main__':
    sys.exit(main())
