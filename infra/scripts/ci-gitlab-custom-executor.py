#!/usr/bin/env python3
"""Unprivileged GitLab Custom executor callback for reviewed isolated MR jobs."""
import json
import os
from pathlib import Path
import signal
import stat
import subprocess
import sys

MAX_CONTEXT = 4 * 1024**2
ROOT_COMMAND = ('sudo', '-n', '/usr/bin/python3', '-I', '-B',
                '/usr/local/lib/feelm-ci222/gitlab/ci-gitlab-job-adapter.py', 'run')
NOOP_STAGES = frozenset({'prepare_script', 'get_sources', 'restore_cache', 'download_artifacts',
                         'after_script', 'archive_cache', 'archive_cache_on_failure',
                         'upload_artifacts_on_success', 'upload_artifacts_on_failure',
                         'cleanup_file_variables'})
FIXED_JOB_STAGES = frozenset({'build_script', 'step_script'})


def exit_code(name, fallback):
    value = os.environ.get(name, str(fallback))
    try: result = int(value)
    except ValueError: return fallback
    return result if 1 <= result <= 255 else fallback


def response_bytes():
    value = os.environ.get('JOB_RESPONSE_FILE')
    if not value: raise ValueError('JOB_RESPONSE_FILE is required')
    path = Path(value)
    info = path.lstat()
    unix_trusted = (not hasattr(os, 'geteuid')
                    or (info.st_uid in (0, os.geteuid()) and not info.st_mode & 0o022))
    if (not stat.S_ISREG(info.st_mode) or not unix_trusted
            or info.st_nlink != 1 or not 0 < info.st_size <= MAX_CONTEXT):
        raise ValueError('Untrusted job response file')
    payload = path.read_bytes()
    json.loads(payload)
    return payload


def script_file(path):
    info = Path(path).lstat()
    unix_trusted = (not hasattr(os, 'geteuid')
                    or (info.st_uid in (0, os.geteuid()) and not info.st_mode & 0o022))
    if (not stat.S_ISREG(info.st_mode) or not unix_trusted
            or info.st_nlink != 1 or info.st_size > MAX_CONTEXT):
        raise ValueError('Untrusted generated script file')


def execute(payload):
    process = subprocess.Popen(ROOT_COMMAND, stdin=subprocess.PIPE)

    def forward(signum, _frame):
        if process.poll() is None: process.send_signal(signum)

    old_term = signal.signal(signal.SIGTERM, forward)
    old_int = signal.signal(signal.SIGINT, forward)
    try:
        process.stdin.write(payload); process.stdin.close()
        return process.wait()
    finally:
        signal.signal(signal.SIGTERM, old_term); signal.signal(signal.SIGINT, old_int)


def build_failure(code):
    path = os.environ.get('BUILD_EXIT_CODE_FILE')
    if path:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0), 0o600)
        with os.fdopen(fd, 'w') as output: output.write(str(code) + '\n')
    return exit_code('BUILD_FAILURE_EXIT_CODE', 1)


def main():
    if len(sys.argv) < 2: return 64
    action = sys.argv[1]
    if action == 'config':
        try: response_bytes()
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            print('MR_EXECUTOR_ERROR: ' + type(exc).__name__, file=sys.stderr)
            return exit_code('SYSTEM_FAILURE_EXIT_CODE', 2)
        print(json.dumps({'driver': {'name': 'feelm-ci222-fixed-mr-jobs', 'version': '2'},
                          'builds_dir': '/var/empty/feelm-ci222-builds',
                          'cache_dir': '/var/empty/feelm-ci222-cache',
                          'builds_dir_is_shared': False}))
        return 0
    if action in ('prepare', 'cleanup'):
        try: response_bytes()
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            print('MR_EXECUTOR_ERROR: ' + type(exc).__name__, file=sys.stderr)
            return exit_code('SYSTEM_FAILURE_EXIT_CODE', 2)
        return 0
    if action != 'run' or len(sys.argv) != 4: return 64
    try: script_file(sys.argv[2])
    except (OSError, ValueError) as exc:
        print('MR_EXECUTOR_ERROR: ' + type(exc).__name__, file=sys.stderr)
        return exit_code('SYSTEM_FAILURE_EXIT_CODE', 2)
    stage = sys.argv[3]
    try: payload = response_bytes()
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print('MR_EXECUTOR_ERROR: ' + type(exc).__name__, file=sys.stderr)
        return exit_code('SYSTEM_FAILURE_EXIT_CODE', 2)
    if stage in NOOP_STAGES: return 0
    if stage not in FIXED_JOB_STAGES:
        print('MR_JOB_REJECTED: unsupported Custom executor stage', file=sys.stderr)
        return build_failure(2)
    try: code = execute(payload)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print('MR_EXECUTOR_ERROR: ' + type(exc).__name__, file=sys.stderr)
        return exit_code('SYSTEM_FAILURE_EXIT_CODE', 2)
    if code == 0: return 0
    if code == 2: return build_failure(code)
    return exit_code('SYSTEM_FAILURE_EXIT_CODE', 2)


if __name__ == '__main__': raise SystemExit(main())
