#!/usr/bin/env python3
"""Root-side, fixed-policy GitLab bridge for the S15P21E106 backend MR job.

The job token is accepted only on stdin, authenticated against GitLab, and is
never forwarded to the workload.  CI-generated shell text is not executed.
"""
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import tarfile
import tempfile
import urllib.error
import urllib.parse
import urllib.request

HERE = Path(__file__).resolve().parent


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec); sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


safety = load('gitlab_safety', 'ci-job-safety.py')
job_io = load('gitlab_job_io', 'ci-job-io.py')
CONFIG = Path('/etc/feelm-ci222/gitlab-adapter.json')
GITLAB_URL = 'https://lab.ssafy.com'
PROJECT_PATH = 's15-bigdata-dist-sub1/S15P21E106'
JOB_NAME = 'backend-mr-test'
JOB_STAGE = 'test'
EXPECTED_SCRIPT = ['cd backend', './gradlew clean test spotlessCheck --no-daemon']
CACHE_STATUS_JOB = 'backend-cache-status'
CACHE_STATUS_SCRIPT = ['echo Backend dependency cache refresh required']
CACHE_DEPENDENCY_FILES = {
    'backend/build.gradle', 'backend/build.gradle.kts',
    'backend/settings.gradle', 'backend/settings.gradle.kts',
    'backend/gradle.properties', 'backend/gradle/libs.versions.toml',
    'backend/gradle/wrapper/gradle-wrapper.properties',
}
MAX_CONTEXT = 4 * 1024**2
MAX_HTTP_JSON = 4 * 1024**2
MAX_HTTP_ARCHIVE = job_io.MAX_ARCHIVE


class PolicyError(Exception):
    pass


class AdapterHttpError(Exception):
    def __init__(self, stage, status):
        super().__init__(stage, status)
        self.stage = stage
        self.status = status


class AdapterGitError(Exception):
    def __init__(self, stage):
        super().__init__(stage)
        self.stage = stage


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise urllib.error.HTTPError(req.full_url, code, 'redirect refused', headers, fp)


def read_private_config(path=CONFIG):
    info = path.lstat()
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077
            or info.st_nlink != 1 or info.st_size > 4096):
        raise RuntimeError('Adapter configuration must be root-owned mode 600')
    value = json.loads(path.read_text())
    if set(value) != {'gitlab_url', 'project_path', 'prepared_base'}:
        raise RuntimeError('Unexpected adapter configuration fields')
    if value['gitlab_url'] != GITLAB_URL or value['project_path'] != PROJECT_PATH:
        raise RuntimeError('Adapter is configured for a different GitLab project')
    prepared = Path(value['prepared_base'])
    safety.pilot.check_base(prepared)
    return value, prepared


def read_stdin():
    payload = sys.stdin.buffer.read(MAX_CONTEXT + 1)
    if not payload or len(payload) > MAX_CONTEXT:
        raise PolicyError('Missing or oversized GitLab job context')
    try: return json.loads(payload)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise PolicyError('Invalid GitLab job context') from exc


def validate_repo_url(value):
    if not isinstance(value, str) or len(value) > 4096:
        raise PolicyError('Invalid repository URL')
    parsed = urllib.parse.urlsplit(value)
    if (parsed.scheme != 'https' or parsed.hostname != 'lab.ssafy.com' or parsed.port not in (None, 443)
            or parsed.path != '/' + PROJECT_PATH + '.git' or parsed.query or parsed.fragment):
        raise PolicyError('Repository URL is outside the fixed project')


def validate_job_response(response):
    if not isinstance(response, dict): raise PolicyError('Job response must be an object')
    job_id = response.get('id')
    token = response.get('token')
    info = response.get('job_info')
    git = response.get('git_info')
    if type(job_id) is not int or job_id <= 0:
        raise PolicyError('Invalid job identity')
    if not isinstance(token, str) or not 16 <= len(token) <= 4096 or any(c.isspace() for c in token):
        raise PolicyError('Invalid job credential')
    policies = {
        JOB_NAME: (JOB_STAGE, EXPECTED_SCRIPT),
        CACHE_STATUS_JOB: ('verify', CACHE_STATUS_SCRIPT),
    }
    policy = policies.get(info.get('name')) if isinstance(info, dict) else None
    if (policy is None or info.get('stage') != policy[0]
            or type(info.get('project_id')) is not int or info['project_id'] <= 0):
        raise PolicyError('Only fixed backend MR jobs are accepted')
    if not isinstance(git, dict) or not re.fullmatch(r'[0-9a-f]{40}|[0-9a-f]{64}', str(git.get('sha', ''))):
        raise PolicyError('Invalid commit identity')
    validate_repo_url(git.get('repo_url'))
    if git.get('ref_type') != 'branch': raise PolicyError('Only branch merge requests are accepted')
    for field in ('services', 'artifacts', 'cache', 'dependencies'):
        if response.get(field) not in (None, []): raise PolicyError('Job services/cache/artifacts are not supported')
    image = response.get('image')
    if image not in (None, {}, {'name': ''}) and (not isinstance(image, dict) or image.get('name')):
        raise PolicyError('Job image override is not supported')
    steps = response.get('steps')
    if not isinstance(steps, list) or len(steps) != 1 or not isinstance(steps[0], dict):
        raise PolicyError('Exactly one fixed CI script step is required')
    if steps[0].get('name') != 'script' or steps[0].get('script') != policy[1]:
        raise PolicyError('CI script differs from the fixed backend policy')
    return {'id': job_id, 'token': token, 'project_id': info['project_id'], 'sha': git['sha'],
            'job_name': info['name'], 'job_stage': info['stage']}


def request(opener, url, token, maximum):
    if not url.startswith(GITLAB_URL + '/api/v4/'):
        raise RuntimeError('Refusing non-GitLab API URL')
    req = urllib.request.Request(url, headers={'JOB-TOKEN': token, 'Accept': 'application/json'})
    with opener.open(req, timeout=30) as response:
        if response.status != 200: raise RuntimeError('GitLab returned a non-success status')
        data = response.read(maximum + 1)
        if len(data) > maximum: raise RuntimeError('GitLab response exceeds policy limit')
        return data


def request_json(opener, url, token):
    try: return json.loads(request(opener, url, token, MAX_HTTP_JSON))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RuntimeError('GitLab returned invalid JSON') from exc


def verify_current_job(opener, context):
    job = request_json(opener, GITLAB_URL + '/api/v4/job', context['token'])
    pipeline = job.get('pipeline') if isinstance(job, dict) else None
    commit = job.get('commit') if isinstance(job, dict) else None
    expected_web = GITLAB_URL + '/' + PROJECT_PATH + '/-/jobs/' + str(context['id'])
    checks = {
        'id': isinstance(job, dict) and job.get('id') == context['id'],
        'name': isinstance(job, dict) and job.get('name') == context['job_name'],
        'stage': isinstance(job, dict) and job.get('stage') == context['job_stage'],
        'web_url': isinstance(job, dict) and job.get('web_url') == expected_web,
        'pipeline': isinstance(pipeline, dict),
        'pipeline.project_id': isinstance(pipeline, dict)
                               and pipeline.get('project_id') == context['project_id'],
        'pipeline.sha': isinstance(pipeline, dict) and pipeline.get('sha') == context['sha'],
        'commit': isinstance(commit, dict),
        'commit.id': isinstance(commit, dict) and commit.get('id') == context['sha'],
    }
    mismatches = [name for name, matched in checks.items() if not matched]
    if mismatches:
        raise PolicyError('Authenticated job metadata mismatch: ' + ','.join(mismatches))


def verify_merge_request(opener, context):
    base = GITLAB_URL + '/api/v4/projects/' + str(context['project_id'])
    url = base + '/repository/commits/' + urllib.parse.quote(context['sha'], safe='') + '/merge_requests'
    values = request_json(opener, url, context['token'])
    if not isinstance(values, list): raise RuntimeError('Unexpected merge request response')
    matches = []
    for mr in values:
        if not isinstance(mr, dict): continue
        head = mr.get('sha') or (mr.get('diff_refs') or {}).get('head_sha')
        if (mr.get('state') == 'opened' and mr.get('target_branch') == 'develop'
                and mr.get('source_project_id') == context['project_id']
                and mr.get('target_project_id') == context['project_id'] and head == context['sha']):
            matches.append(mr)
    if len(matches) != 1: raise PolicyError('Commit must belong to one open same-project MR targeting develop')
    return matches[0]


def fetch_merge_request_detail(opener, context, summary):
    iid = summary.get('iid') if isinstance(summary, dict) else None
    if type(iid) is not int or iid <= 0:
        raise PolicyError('Merge request identity is missing')
    base = GITLAB_URL + '/api/v4/projects/' + str(context['project_id'])
    detail = request_json(opener, base + '/merge_requests/' + str(iid), context['token'])
    diff_refs = detail.get('diff_refs') if isinstance(detail, dict) else None
    head_sha = diff_refs.get('head_sha') if isinstance(diff_refs, dict) else None
    checks = {
        'iid': isinstance(detail, dict) and detail.get('iid') == iid,
        'state': isinstance(detail, dict) and detail.get('state') == 'opened',
        'target_branch': isinstance(detail, dict) and detail.get('target_branch') == 'develop',
        'source_project_id': isinstance(detail, dict)
                             and detail.get('source_project_id') == context['project_id'],
        'target_project_id': isinstance(detail, dict)
                             and detail.get('target_project_id') == context['project_id'],
        'sha': isinstance(detail, dict) and detail.get('sha') == context['sha'],
        'diff_refs': isinstance(diff_refs, dict),
        'diff_refs.head_sha': head_sha == context['sha'],
        'diff_refs.base_sha': isinstance(diff_refs, dict)
                              and re.fullmatch(r'[0-9a-f]{40}', str(diff_refs.get('base_sha', '')))
                              is not None,
        'diff_refs.start_sha': isinstance(diff_refs, dict)
                               and re.fullmatch(r'[0-9a-f]{40}', str(diff_refs.get('start_sha', '')))
                               is not None,
    }
    mismatches = [name for name, matched in checks.items() if not matched]
    if mismatches:
        raise PolicyError('Merge request detail mismatch: ' + ','.join(mismatches))
    return detail


def git_environment(token, home):
    authorization = __import__('base64').b64encode(
        ('gitlab-ci-token:' + token).encode()).decode()
    return {
        'PATH': '/usr/bin:/bin', 'HOME': str(home), 'LANG': 'C.UTF-8',
        'GIT_TERMINAL_PROMPT': '0', 'GIT_CONFIG_NOSYSTEM': '1',
        'GIT_CONFIG_GLOBAL': '/dev/null', 'GIT_CONFIG_COUNT': '2',
        'GIT_CONFIG_KEY_0': 'http.extraHeader',
        'GIT_CONFIG_VALUE_0': 'Authorization: Basic ' + authorization,
        'GIT_CONFIG_KEY_1': 'http.followRedirects', 'GIT_CONFIG_VALUE_1': 'false',
    }


def run_git(command, environment, stage, **options):
    try:
        return subprocess.run(command, env=environment, check=True, timeout=120, **options)
    except (OSError, subprocess.SubprocessError) as exc:
        raise AdapterGitError(stage) from exc


def verify_dependency_change(context, mr):
    iid = mr.get('iid')
    if type(iid) is not int or iid <= 0:
        raise PolicyError('Merge request identity is missing')
    diff_refs = mr.get('diff_refs') if isinstance(mr, dict) else None
    base_sha = diff_refs.get('base_sha') if isinstance(diff_refs, dict) else None
    head_sha = diff_refs.get('head_sha') if isinstance(diff_refs, dict) else None
    if (re.fullmatch(r'[0-9a-f]{40}', str(base_sha or '')) is None
            or head_sha != context['sha']):
        raise PolicyError('Merge request diff revisions are missing or inconsistent')
    with tempfile.TemporaryDirectory(prefix='ci271-status-diff-') as directory:
        repository = Path(directory) / 'repository.git'
        environment = git_environment(context['token'], directory)
        remote = GITLAB_URL + '/' + PROJECT_PATH + '.git'
        quiet = {'stdout': subprocess.DEVNULL, 'stderr': subprocess.DEVNULL}
        run_git(['/usr/bin/git', 'init', '--bare', str(repository)], environment,
                'dependency_git_init', **quiet)
        for revision, reference in ((base_sha, 'base'), (head_sha, 'head')):
            run_git(['/usr/bin/git', '-C', str(repository), 'fetch', '--no-tags', '--depth=1',
                     remote, revision + ':refs/review/' + reference], environment,
                    'dependency_fetch', **quiet)
        names = run_git(
            ['/usr/bin/git', '-C', str(repository), 'diff', '--name-only', '-z',
             '--no-ext-diff', '--no-renames', 'refs/review/base', 'refs/review/head'],
            environment, 'dependency_diff', capture_output=True).stdout
    if len(names) > 128 * 1024:
        raise PolicyError('Merge request diff is missing or too large')
    try:
        raw_paths = names.decode('utf-8').split('\0') if names else []
    except UnicodeDecodeError as exc:
        raise PolicyError('Merge request diff contains an invalid path encoding') from exc
    paths = sorted(set(path for path in raw_paths if path))
    if not paths or len(paths) >= 100 or any('\n' in path or '\r' in path for path in paths):
        raise PolicyError('Merge request diff is missing or too large')
    changes = [path for path in paths if path in CACHE_DEPENDENCY_FILES]
    if not changes:
        raise PolicyError('No approved backend dependency change found')
    print('CACHE_REFRESH_REQUIRED_MR=' + str(iid))
    print('CACHE_REFRESH_REVISION=' + context['sha'])
    print('CACHE_DEPENDENCY_CHANGES=' + json.dumps(changes))
    return changes


def normalized_backend_archive(payload):
    if not payload or len(payload) > MAX_HTTP_ARCHIVE:
        raise PolicyError('Repository archive exceeds policy limit')
    entries = []
    prefix = None
    total = 0
    try:
        with tarfile.open(fileobj=io.BytesIO(payload), mode='r:*') as source:
            for entry in source:
                parts = entry.name.rstrip('/').split('/')
                if not parts[0]: raise PolicyError('Archive prefix is missing')
                if prefix is None: prefix = parts[0]
                if parts[0] != prefix: raise PolicyError('Archive has multiple roots')
                if len(parts) == 1 and entry.isdir(): continue
                if len(parts) < 2: raise PolicyError('Archive member is outside its root')
                name = '/'.join(parts[1:])
                if name != 'backend' and not name.startswith('backend/'):
                    continue
                if not name or not (entry.isdir() or entry.isfile()) or entry.sparse is not None:
                    raise PolicyError('Unsupported repository archive member')
                data = b''
                if entry.isfile():
                    total += entry.size
                    if entry.size < 0 or entry.size > job_io.MAX_SOURCE_FILE or total > job_io.MAX_ARCHIVE:
                        raise PolicyError('Repository archive expands beyond policy')
                    stream = source.extractfile(entry)
                    if stream is None: raise PolicyError('Missing repository archive data')
                    data = stream.read(job_io.MAX_SOURCE_FILE + 1)
                    if len(data) != entry.size: raise PolicyError('Truncated repository archive member')
                entries.append((name, entry.isdir(), data, entry.mode))
    except tarfile.TarError as exc:
        raise PolicyError('Invalid repository tar archive') from exc
    if not entries: raise PolicyError('Repository archive has no backend source')
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode='w') as target:
        for name, directory, data, mode in entries:
            item = tarfile.TarInfo(name + ('/' if directory else ''))
            item.type = tarfile.DIRTYPE if directory else tarfile.REGTYPE
            item.mode = 0o700 if directory or mode & 0o111 else 0o600
            item.size = 0 if directory else len(data)
            target.addfile(item, None if directory else io.BytesIO(data))
            if output.tell() > job_io.MAX_ARCHIVE: raise PolicyError('Normalized archive exceeds policy')
    result = output.getvalue()
    digest = hashlib.sha256(result).hexdigest()
    job_io.source_plan(result, digest)
    return result, digest


def fetch_source(context):
    """Fetch one authenticated, verified commit without exposing the job token."""
    with tempfile.TemporaryDirectory(prefix='ci222-git-') as directory:
        repository = Path(directory) / 'repository.git'
        environment = git_environment(context['token'], directory)
        quiet = {'stdout': subprocess.DEVNULL, 'stderr': subprocess.DEVNULL,
                 'check': True, 'timeout': 120}
        subprocess.run(['/usr/bin/git', 'init', '--bare', str(repository)],
                       env=environment, **quiet)
        subprocess.run(['/usr/bin/git', '-C', str(repository), 'fetch', '--no-tags', '--depth=1',
                        GITLAB_URL + '/' + PROJECT_PATH + '.git', context['sha']],
                       env=environment, **quiet)
        archive_path = Path(directory) / 'source.tar'
        with archive_path.open('xb') as output:
            subprocess.run(['/usr/bin/git', '-C', str(repository), 'archive', '--format=tar',
                            '--prefix=source/', 'FETCH_HEAD', 'backend'],
                           env=environment, stdout=output, stderr=subprocess.DEVNULL,
                           check=True, timeout=60)
        if archive_path.stat().st_size > MAX_HTTP_ARCHIVE:
            raise PolicyError('Repository archive exceeds policy limit')
        return normalized_backend_archive(archive_path.read_bytes())


def stop_owner_unit(unit):
    if not re.fullmatch(r'ci222-owner-[a-f0-9]{12}\.service', unit):
        raise ValueError('Refusing unrelated owner unit')
    result = subprocess.run(['systemctl', 'stop', unit], capture_output=True, timeout=120)
    if result.returncode != 0:
        raise RuntimeError('Owner cleanup service failed')


def run_job(response, config_path=CONFIG, opener=None):
    context = validate_job_response(response)
    _, prepared = read_private_config(config_path)
    opener = opener or urllib.request.build_opener(NoRedirect())
    try: verify_current_job(opener, context)
    except urllib.error.HTTPError as exc: raise AdapterHttpError('current_job', exc.code) from exc
    try: mr = verify_merge_request(opener, context)
    except urllib.error.HTTPError as exc: raise AdapterHttpError('merge_request', exc.code) from exc
    if context['job_name'] == CACHE_STATUS_JOB:
        try: detail = fetch_merge_request_detail(opener, context, mr)
        except urllib.error.HTTPError as exc:
            raise AdapterHttpError('merge_request_detail', exc.code) from exc
        verify_dependency_change(context, detail)
        return 0
    payload, digest = fetch_source(context)
    active = {}

    def started(attempt, lease):
        active.update(attempt=attempt, lease=lease)

    def interrupted(signum, _frame):
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        lease = active.get('lease')
        if lease is not None:
            stop_owner_unit(lease['owner'])
        raise KeyboardInterrupt('GitLab Runner interrupted the job')

    previous_term = signal.signal(signal.SIGTERM, interrupted)
    previous_int = signal.signal(signal.SIGINT, interrupted)
    try:
        return safety.start(prepared, prepared, 'backend', payload, digest, started)
    finally:
        signal.signal(signal.SIGTERM, previous_term); signal.signal(signal.SIGINT, previous_int)


def main():
    if sys.argv[1:] != ['run']:
        print('USAGE: ci-gitlab-job-adapter.py run', file=sys.stderr); return 64
    try: return run_job(read_stdin())
    except PolicyError as exc:
        print('MR_JOB_REJECTED: ' + str(exc), file=sys.stderr); return 2
    except KeyboardInterrupt:
        print('MR_JOB_CANCELLED: cleanup requested', file=sys.stderr); return 2
    except AdapterHttpError as exc:
        print('MR_ADAPTER_HTTP_ERROR: stage=' + exc.stage + ' status=' + str(exc.status),
              file=sys.stderr)
        return 70
    except AdapterGitError as exc:
        print('MR_ADAPTER_GIT_ERROR: stage=' + exc.stage, file=sys.stderr)
        return 70
    except Exception as exc:
        print('MR_ADAPTER_ERROR: ' + type(exc).__name__, file=sys.stderr); return 70


if __name__ == '__main__': raise SystemExit(main())
