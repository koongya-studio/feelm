#!/usr/bin/env python3
"""Root-side, fixed-policy GitLab bridge for S15P21E106 isolated MR jobs.

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
runtime = load('gitlab_runtime_contract', 'ci-backend-runtime-contract.py')
frontend_boundary = load('gitlab_frontend_boundary', 'ci-frontend-mr-boundary.py')
frontend_runner = load('gitlab_frontend_runner', 'ci-frontend-mr-runner.py')
pipeline_boundary = load('gitlab_pipeline_boundary', 'ci-pipeline-mr-boundary.py')
pipeline_runner = load('gitlab_pipeline_runner', 'ci-pipeline-mr-runner.py')
consumer_boundary = load('gitlab_consumer_boundary', 'ci-consumer-mr-boundary.py')
consumer_runner = load('gitlab_consumer_runner', 'ci-consumer-mr-runner.py')
infra_boundary = load('gitlab_infra_boundary', 'ci-infra-mr-boundary.py')
infra_runner = load('gitlab_infra_runner', 'ci-infra-mr-runner.py')
docs_boundary = load('gitlab_docs_boundary', 'ci-docs-mr-boundary.py')
docs_runner = load('gitlab_docs_runner', 'ci-docs-mr-runner.py')
dependency_cache = load('gitlab_dependency_cache', 'ci-dependency-cache.py')
CONFIG = Path('/etc/feelm-ci222/gitlab-adapter.json')
GITLAB_URL = 'https://lab.ssafy.com'
PROJECT_PATH = 's15-bigdata-dist-sub1/S15P21E106'
JOB_NAME = 'backend-mr-test'
JOB_STAGE = 'test'
EXPECTED_SCRIPT = ['cd backend', './gradlew clean test spotlessCheck --no-daemon']
FRONTEND_JOB_NAME = 'frontend-mr-test'
FRONTEND_EXPECTED_SCRIPT = [
    'cd frontend', 'npm ci', 'npm run lint', 'npm run format:check', 'npm run test:run', 'npm run build',
]
CONSUMER_JOB_NAME = 'consumer-mr-test'
CONSUMER_EXPECTED_SCRIPT = [
    'cd pipeline/consumer', 'uv sync --frozen',
    'uv run ruff check .', 'uv run pytest -v',
]
INFRA_JOB_NAME = 'infra-config-test'
INFRA_EXPECTED_SCRIPT = [
    'python3 -I -B infra/scripts/run-infra-config-tests.py',
]
CI_VARIABLE_CONTRACT_JOB = 'ci-variable-contract-check'
CI_VARIABLE_CONTRACT_SCRIPT = [
    'python3 -I -B infra/scripts/ci-required-variable-preflight.py',
]
DOCS_JOB_NAME = 'docs-mr-check'
DOCS_EXPECTED_SCRIPT = ['python3 -B infra/scripts/check-markdown.py']
PIPELINE_JOB_GROUPS = {
    'pipeline-unit-test': 'unit',
    'pipeline-spark-als-a-d-test': 'spark-als-a-d',
    'pipeline-spark-als-e-z-test': 'spark-als-e-z',
    'pipeline-spark-other-test': 'spark-other',
}
PIPELINE_JOB_SCRIPTS = {
    'pipeline-unit-test': [
        'cd pipeline/spark', 'uv sync --frozen', 'uv run ruff check .',
        'uv run python -m ci_test_groups verify',
        'uv run python -m ci_test_groups unit -vv --durations=20',
        'uv run python -m jobs.batch.als_baseline --help',
        'uv run python -m jobs.batch.parquet_roundtrip --help',
    ],
    'pipeline-spark-als-a-d-test': [
        'cd pipeline/spark', 'uv sync --frozen',
        'uv run python -m ci_test_groups spark-als-a-d -vv --durations=20',
    ],
    'pipeline-spark-als-e-z-test': [
        'cd pipeline/spark', 'uv sync --frozen',
        'uv run python -m ci_test_groups spark-als-e-z -vv --durations=20',
    ],
    'pipeline-spark-other-test': [
        'cd pipeline/spark', 'uv sync --frozen',
        'uv run python -m ci_test_groups spark-other -vv --durations=20',
    ],
}
CACHE_PREPARE_JOB = 'backend-cache-prepare'
CACHE_PREPARE_SCRIPT = ['echo Prepare verified backend dependency cache for this MR revision']
CACHE_DEPENDENCY_FILES = dependency_cache.DEPENDENCY_FILES
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


class AdapterStageError(Exception):
    """Report only a fixed adapter stage and exception type, never exception data."""
    def __init__(self, stage, error_type):
        super().__init__(stage, error_type)
        self.stage = stage
        self.error_type = error_type


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise urllib.error.HTTPError(req.full_url, code, 'redirect refused', headers, fp)


def read_private_config(path=CONFIG, require_prepared=True):
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
    if require_prepared:
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
        JOB_NAME: (JOB_STAGE, EXPECTED_SCRIPT, 'backend', None),
        FRONTEND_JOB_NAME: (JOB_STAGE, FRONTEND_EXPECTED_SCRIPT, 'frontend', None),
        CONSUMER_JOB_NAME: (JOB_STAGE, CONSUMER_EXPECTED_SCRIPT, 'consumer', None),
        INFRA_JOB_NAME: (JOB_STAGE, INFRA_EXPECTED_SCRIPT, 'infra', None),
        CI_VARIABLE_CONTRACT_JOB: (
            'verify', CI_VARIABLE_CONTRACT_SCRIPT, 'infra', 'variable-contract'
        ),
        DOCS_JOB_NAME: ('verify', DOCS_EXPECTED_SCRIPT, 'docs', None),
        CACHE_PREPARE_JOB: ('verify', CACHE_PREPARE_SCRIPT, 'cache', None),
        **{
            name: (JOB_STAGE, PIPELINE_JOB_SCRIPTS[name], 'pipeline', group)
            for name, group in PIPELINE_JOB_GROUPS.items()
        },
    }
    policy = policies.get(info.get('name')) if isinstance(info, dict) else None
    if (policy is None or info.get('stage') != policy[0]
            or type(info.get('project_id')) is not int or info['project_id'] <= 0):
        raise PolicyError('Only fixed isolated MR jobs are accepted')
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
        raise PolicyError('CI script differs from the fixed isolated policy')
    return {'id': job_id, 'token': token, 'project_id': info['project_id'], 'sha': git['sha'],
            'job_name': info['name'], 'job_stage': info['stage'], 'service': policy[2],
            'pipeline_group': policy[3]}


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
        'pipeline.id': isinstance(pipeline, dict) and type(pipeline.get('id')) is int
                       and pipeline['id'] > 0,
        'pipeline.project_id': isinstance(pipeline, dict)
                               and pipeline.get('project_id') == context['project_id'],
        'pipeline.sha': isinstance(pipeline, dict) and pipeline.get('sha') == context['sha'],
        'commit': isinstance(commit, dict),
        'commit.id': isinstance(commit, dict) and commit.get('id') == context['sha'],
    }
    mismatches = [name for name, matched in checks.items() if not matched]
    if mismatches:
        raise PolicyError('Authenticated job metadata mismatch: ' + ','.join(mismatches))
    return job


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


def dependency_changes(context, mr):
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
        dependency_files = [path for path in paths if path in CACHE_DEPENDENCY_FILES]
        if not dependency_files:
            return []
        diff = run_git(
            ['/usr/bin/git', '-C', str(repository), 'diff', '--unified=0', '--no-ext-diff',
             '--no-renames', 'refs/review/base', 'refs/review/head', '--', *dependency_files],
            environment, 'dependency_declaration_diff', capture_output=True).stdout
    if len(diff) > 512 * 1024:
        raise PolicyError('Dependency diff is missing or oversized')
    try:
        changes, _ = dependency_cache.classify_dependency_changes(
            paths, diff.decode('utf-8'))
    except UnicodeDecodeError as exc:
        raise PolicyError('Dependency diff contains an invalid encoding') from exc
    return changes


def verified_candidate(context, mr_iid):
    manager = load('gitlab_dependency_cache', 'ci-dependency-cache.py')
    try:
        return manager.prepared_candidate(context['sha'], mr_iid)
    except manager.PolicyError as exc:
        raise PolicyError(str(exc)) from exc


def prepare_candidate(context, job, mr):
    manager = load('gitlab_dependency_cache_prepare', 'ci-dependency-cache.py')
    manager_context = {
        'action': 'prepare', 'project_id': context['project_id'],
        'pipeline_id': job['pipeline']['id'], 'job_id': context['id'],
        'mr_iid': mr['iid'], 'revision': context['sha'], 'token': context['token'],
    }
    try:
        manager.automatic_prepare(manager_context)
    except manager.PolicyError as exc:
        raise PolicyError(str(exc)) from exc


def normalized_service_archive(payload, service):
    if service not in ('backend', 'frontend', 'pipeline', 'consumer', 'infra'):
        raise PolicyError('Unsupported source service')
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
                backend_contract = name in ('infra', 'infra/compose', 'infra/compose/service.yml')
                in_scope = (name == service or name.startswith(service + '/'))
                if service == 'backend' and backend_contract:
                    in_scope = True
                if service == 'pipeline':
                    in_scope = (name == 'pipeline' or name == 'pipeline/spark'
                                or name.startswith('pipeline/spark/'))
                if service == 'consumer':
                    in_scope = (name == 'pipeline' or name == 'pipeline/consumer'
                                or name.startswith('pipeline/consumer/'))
                if service == 'infra':
                    in_scope = (name == '.gitlab-ci.yml' or name == '.gitlab'
                                or name.startswith('.gitlab/')
                                or name == 'infra' or name.startswith('infra/'))
                if not in_scope:
                    if service == 'frontend':
                        raise PolicyError('Repository archive contains out-of-scope source')
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
    if not entries: raise PolicyError('Repository archive has no ' + service + ' source')
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
    try:
        if service == 'backend':
            job_io.source_plan(result, digest)
        elif service == 'frontend':
            frontend_boundary.source_plan(result, digest)
        elif service == 'pipeline':
            pipeline_boundary.source_plan(result, digest)
        elif service == 'consumer':
            consumer_boundary.source_plan(result, digest)
        else:
            infra_boundary.source_plan(result, digest)
    except ValueError as exc:
        raise PolicyError(str(exc)) from exc
    return result, digest


def normalized_backend_archive(payload):
    return normalized_service_archive(payload, 'backend')


def normalized_docs_archive(payload):
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
                if not name or not (entry.isdir() or entry.isfile()) or entry.sparse is not None:
                    raise PolicyError('Unsupported repository archive member')
                if entry.isfile() and not (name.casefold().endswith('.md')
                                           or name == docs_boundary.MANIFEST):
                    raise PolicyError('Repository archive contains out-of-scope documentation source')
                data = b''
                if entry.isfile():
                    total += entry.size
                    if entry.size < 0 or entry.size > docs_boundary.MAX_SOURCE_FILE or total > docs_boundary.MAX_ARCHIVE:
                        raise PolicyError('Repository archive expands beyond policy')
                    stream = source.extractfile(entry)
                    if stream is None: raise PolicyError('Missing repository archive data')
                    data = stream.read(docs_boundary.MAX_SOURCE_FILE + 1)
                    if len(data) != entry.size: raise PolicyError('Truncated repository archive member')
                entries.append((name, entry.isdir(), data))
    except tarfile.TarError as exc:
        raise PolicyError('Invalid repository tar archive') from exc
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode='w') as target:
        for name, directory, data in entries:
            item = tarfile.TarInfo(name + ('/' if directory else ''))
            item.type = tarfile.DIRTYPE if directory else tarfile.REGTYPE
            item.mode = 0o700 if directory else 0o600
            item.size = 0 if directory else len(data)
            target.addfile(item, None if directory else io.BytesIO(data))
    result = output.getvalue()
    digest = hashlib.sha256(result).hexdigest()
    try:
        docs_boundary.source_plan(result, digest)
    except ValueError as exc:
        raise PolicyError(str(exc)) from exc
    return result, digest


def fetch_docs_source(context):
    """Fetch Markdown content and a full tracked-path manifest for link validation."""
    with tempfile.TemporaryDirectory(prefix='ci296-git-') as directory:
        repository = Path(directory) / 'repository.git'
        environment = git_environment(context['token'], directory)
        quiet = {'stdout': subprocess.DEVNULL, 'stderr': subprocess.DEVNULL}
        run_git(['/usr/bin/git', 'init', '--bare', str(repository)], environment,
                'docs_git_init', **quiet)
        run_git(['/usr/bin/git', '-C', str(repository), 'fetch', '--no-tags', '--depth=1',
                 GITLAB_URL + '/' + PROJECT_PATH + '.git', context['sha']], environment,
                'docs_git_fetch', **quiet)
        listing = run_git(
            ['/usr/bin/git', '-C', str(repository), 'ls-tree', '-r', '-z', '--name-only', 'FETCH_HEAD'],
            environment, 'docs_git_tree', stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL).stdout
        if len(listing) > 8 * 1024**2:
            raise PolicyError('Tracked-path manifest exceeds policy limit')
        try:
            paths = listing.decode('utf-8').rstrip('\0').split('\0')
        except UnicodeDecodeError as exc:
            raise PolicyError('Repository contains invalid path encoding') from exc
        paths = sorted(path for path in paths if path)
        try:
            for path in paths:
                docs_boundary.relative_parts(path)
        except ValueError as exc:
            raise PolicyError(str(exc)) from exc
        if not paths or paths != sorted(set(paths)):
            raise PolicyError('Tracked paths are missing or duplicated')
        markdown = [path for path in paths if path.casefold().endswith('.md')]
        if not markdown:
            raise PolicyError('Repository has no Markdown source')
        archive_path = Path(directory) / 'source.tar'
        with archive_path.open('xb') as output:
            run_git(['/usr/bin/git', '-C', str(repository), 'archive', '--format=tar',
                     '--prefix=source/', 'FETCH_HEAD', '--', *markdown], environment,
                    'docs_git_archive', stdout=output, stderr=subprocess.DEVNULL)
        try:
            manifest = json.dumps(paths, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
            with tarfile.open(archive_path, mode='a') as archive:
                item = tarfile.TarInfo('source/' + docs_boundary.MANIFEST)
                item.mode = 0o600
                item.size = len(manifest)
                archive.addfile(item, io.BytesIO(manifest))
        except (OSError, TypeError, ValueError, tarfile.TarError) as exc:
            raise AdapterStageError('docs_manifest_append', type(exc).__name__) from exc
        if archive_path.stat().st_size > MAX_HTTP_ARCHIVE:
            raise PolicyError('Repository archive exceeds policy limit')
        try:
            return normalized_docs_archive(archive_path.read_bytes())
        except PolicyError:
            raise
        except Exception as exc:
            raise AdapterStageError('docs_archive_normalize', type(exc).__name__) from exc


def fetch_source(context, service='backend'):
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
            source_paths = ['pipeline/consumer'] if service == 'consumer' else [service]
            if service == 'infra':
                source_paths = ['.gitlab-ci.yml', '.gitlab', 'infra']
            if service == 'backend':
                source_paths.append('infra/compose/service.yml')
            subprocess.run(['/usr/bin/git', '-C', str(repository), 'archive', '--format=tar',
                            '--prefix=source/', 'FETCH_HEAD', *source_paths],
                           env=environment, stdout=output, stderr=subprocess.DEVNULL,
                           check=True, timeout=60)
        if archive_path.stat().st_size > MAX_HTTP_ARCHIVE:
            raise PolicyError('Repository archive exceeds policy limit')
        return normalized_service_archive(archive_path.read_bytes(), service)


def stop_owner_unit(unit):
    if not re.fullmatch(r'(?:ci(?:222|296|352|486)-owner|ci490-infra)-[a-f0-9]{12}\.service', unit):
        raise ValueError('Refusing unrelated owner unit')
    result = subprocess.run(['systemctl', 'stop', unit], capture_output=True, timeout=120)
    if result.returncode != 0:
        raise RuntimeError('Owner cleanup service failed')


def run_job(response, config_path=CONFIG, opener=None):
    context = validate_job_response(response)
    _, prepared = read_private_config(
        config_path,
        require_prepared=context['service'] not in ('frontend', 'consumer', 'infra', 'docs'))
    if context['service'] == 'backend':
        try:
            runtime.require_same(HERE, prepared / 'scripts')
        except (RuntimeError, ValueError, OSError) as exc:
            raise AdapterStageError('backend_runtime_contract', type(exc).__name__) from exc
    opener = opener or urllib.request.build_opener(NoRedirect())
    try: job = verify_current_job(opener, context)
    except urllib.error.HTTPError as exc: raise AdapterHttpError('current_job', exc.code) from exc
    try: mr = verify_merge_request(opener, context)
    except urllib.error.HTTPError as exc: raise AdapterHttpError('merge_request', exc.code) from exc
    try: detail = fetch_merge_request_detail(opener, context, mr)
    except urllib.error.HTTPError as exc:
        raise AdapterHttpError('merge_request_detail', exc.code) from exc
    changes = dependency_changes(context, detail) if context['service'] in ('backend', 'cache') else []
    if context['job_name'] == CACHE_PREPARE_JOB:
        if not changes:
            print('CACHE_PREPARE_SKIPPED=NO_DEPENDENCY_CHANGE')
            return 0
        prepare_candidate(context, job, detail)
        return 0
    if changes and context['service'] == 'backend':
        prepared = verified_candidate(context, detail['iid'])
    if context['service'] == 'docs':
        try:
            payload, digest = fetch_docs_source(context)
        except (PolicyError, AdapterGitError, AdapterStageError):
            raise
        except Exception as exc:
            raise AdapterStageError('docs_source_prepare', type(exc).__name__) from exc
    else:
        payload, digest = fetch_source(context, context['service'])
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
        if context['service'] == 'frontend':
            return frontend_runner.start(payload, digest, started)
        if context['service'] == 'docs':
            try:
                return docs_runner.start(payload, digest, started)
            except Exception as exc:
                raise AdapterStageError('docs_runner_start', type(exc).__name__) from exc
        if context['service'] == 'pipeline':
            return pipeline_runner.start(payload, digest, context['pipeline_group'], started)
        if context['service'] == 'consumer':
            return consumer_runner.start(payload, digest, started)
        if context['service'] == 'infra':
            return infra_runner.start(payload, digest, context['pipeline_group'], started)
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
    except AdapterStageError as exc:
        print('MR_ADAPTER_STAGE_ERROR: stage=' + exc.stage + ' type=' + exc.error_type,
              file=sys.stderr)
        return 70
    except Exception as exc:
        print('MR_ADAPTER_ERROR: ' + type(exc).__name__, file=sys.stderr); return 70


if __name__ == '__main__': raise SystemExit(main())
