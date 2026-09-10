#!/usr/bin/env python3
"""Prepare, verify, promote and roll back reviewed backend cache stages."""
try:
    import fcntl
except ImportError:  # Windows unit-test collection only; execution is Linux-only.
    fcntl = None
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
GITLAB_URL = 'https://lab.ssafy.com'
PROJECT_PATH = 's15-bigdata-dist-sub1/S15P21E106'
PROJECT_API = urllib.parse.quote(PROJECT_PATH, safe='')
PROJECT_ID = 1441379
STATE = Path('/var/lib/feelm-ci271')
ARCHIVES = Path('/home/feelm-ci-test/cache-archives')
ADAPTER_CONFIG = Path('/etc/feelm-ci222/gitlab-adapter.json')
RUNNER_SERVICE = 'feelm-ci222-gitlab-runner.service'
LOCK = Path('/run/lock/feelm-ci271.lock')
OPERATORS = HERE / 'cache-operators.json'
MAX_JSON = 8 * 1024**2
DEPENDENCY_FILES = {
    'backend/build.gradle', 'backend/build.gradle.kts',
    'backend/settings.gradle', 'backend/settings.gradle.kts',
    'backend/gradle.properties', 'backend/gradle/libs.versions.toml',
    'backend/gradle/wrapper/gradle-wrapper.properties',
}
STAGE_FILES = (
    'ci-job-safety.py', 'ci-bounded-output.py', 'ci-job-io.py', 'ci-job-lifecycle.py',
    'ci-server-manager-pilot.py', 'ci-server-sysfs-bootstrap.py', 'ci-local-manager-check.py',
    'ci-local-backend-runtime.py', 'ci-local-backend-inputs.py', 'ci-local-backend-transfer.py',
    'ci-backend-offline-prepare.py', 'ci-rootless-backend-check.py', 'ci-rootless-network-check.py',
    'rootless-backend/LoopbackContainers.java', 'rootless-backend/pilot.init.gradle',
    'rootless-backend/META-INF/services/org.testcontainers.core.CreateContainerCmdModifier',
)


class PolicyError(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        raise urllib.error.HTTPError(request.full_url, code, 'redirect refused', headers,
                                     file_pointer)


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        while chunk := source.read(1024 * 1024):
            value.update(chunk)
    return value.hexdigest()


def read_context(stream=None):
    raw = (stream or sys.stdin.buffer).read(64 * 1024 + 1)
    if not raw or len(raw) > 64 * 1024:
        raise PolicyError('Missing or oversized CI context')
    try:
        value = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise PolicyError('Invalid CI context') from exc
    if set(value) != {'action', 'project_id', 'pipeline_id', 'job_id', 'mr_iid',
                      'revision', 'token'}:
        raise PolicyError('Unexpected CI context fields')
    if value['action'] not in {'promote', 'rollback'}:
        raise PolicyError('Unsupported action')
    if type(value['project_id']) is not int or value['project_id'] <= 0:
        raise PolicyError('Invalid project identity')
    if type(value['pipeline_id']) is not int or value['pipeline_id'] <= 0:
        raise PolicyError('Invalid pipeline identity')
    if type(value['job_id']) is not int or value['job_id'] <= 0:
        raise PolicyError('Invalid job identity')
    if (value['action'] != 'promote'
            and (type(value['mr_iid']) is not int or value['mr_iid'] <= 0)):
        raise PolicyError('Invalid MR identity')
    if value['action'] == 'promote' and value['mr_iid'] is not None:
        raise PolicyError('Automatic promotion discovers its merged MR')
    if not re.fullmatch(r'[0-9a-f]{40}', str(value['revision'])):
        raise PolicyError('Invalid revision')
    token = value['token']
    if not isinstance(token, str) or not 16 <= len(token) <= 4096 or any(c.isspace() for c in token):
        raise PolicyError('Invalid job credential')
    return value


def api_json(path, token, opener=None):
    url = GITLAB_URL + '/api/v4/' + path.lstrip('/')
    request = urllib.request.Request(url, headers={'JOB-TOKEN': token, 'Accept': 'application/json'})
    with (opener or urllib.request.build_opener(NoRedirect())).open(request, timeout=30) as response:
        if response.status != 200:
            raise RuntimeError('GitLab returned a non-success status')
        raw = response.read(MAX_JSON + 1)
    if len(raw) > MAX_JSON:
        raise RuntimeError('GitLab response exceeds policy limit')
    return json.loads(raw)


def operator_usernames():
    info = OPERATORS.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077:
        raise PolicyError('Cache operator policy is not trusted root-owned state')
    value = json.loads(OPERATORS.read_text())
    usernames = value.get('usernames') if isinstance(value, dict) and set(value) == {'usernames'} else None
    if (not isinstance(usernames, list) or not usernames or usernames != sorted(set(usernames))
            or any(not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9_.-]{1,64}', name)
                   for name in usernames)):
        raise PolicyError('Cache operator policy is invalid')
    return set(usernames)


def verify_job(context, opener=None):
    job = api_json('job', context['token'], opener)
    if not isinstance(job, dict):
        raise PolicyError('Current GitLab job is not authorized: response')
    pipeline = job.get('pipeline')
    commit = job.get('commit')
    user = job.get('user')
    expected_name = 'backend-cache-' + context['action']
    checks = (
        ('pipeline', isinstance(pipeline, dict)),
        ('commit', isinstance(commit, dict)),
        ('user', isinstance(user, dict)),
        ('job_id', job.get('id') == context['job_id']),
        ('job_name', job.get('name') == expected_name),
        ('job_stage', job.get('stage') == 'verify'),
        ('job_status', job.get('status') == 'running'),
        ('job_ref', job.get('ref') == 'develop'),
        ('project', context['project_id'] == PROJECT_ID),
        ('pipeline_id', isinstance(pipeline, dict)
         and pipeline.get('id') == context['pipeline_id']),
        ('pipeline_project', isinstance(pipeline, dict)
         and pipeline.get('project_id') == context['project_id']),
        ('pipeline_ref', isinstance(pipeline, dict) and pipeline.get('ref') == 'develop'),
        ('pipeline_sha', isinstance(pipeline, dict)
         and re.fullmatch(r'[0-9a-f]{40}', str(pipeline.get('sha', ''))) is not None),
        ('commit_sha', isinstance(commit, dict) and isinstance(pipeline, dict)
         and commit.get('id') == pipeline.get('sha')),
        ('user_id', isinstance(user, dict) and type(user.get('id')) is int
         and user['id'] > 0),
        ('user_state', isinstance(user, dict) and user.get('state') == 'active'),
    )
    failures = [name for name, passed in checks if not passed]
    if failures:
        raise PolicyError('Current GitLab job is not authorized: ' + ','.join(failures))
    if context['action'] == 'promote' and pipeline.get('sha') != context['revision']:
        raise PolicyError('Automatic promotion revision does not match the develop pipeline')
    if context['action'] == 'rollback' and user.get('username') not in operator_usernames():
        raise PolicyError('Current GitLab cache operator is not authorized')
    return 'automatic-develop-pipeline' if context['action'] == 'promote' else user['username']


def merged_mr(context, opener=None):
    path = (f'projects/{PROJECT_ID}/repository/commits/'
            f'{urllib.parse.quote(context["revision"], safe="")}/merge_requests')
    summaries = api_json(path, context['token'], opener)
    if not isinstance(summaries, list) or len(summaries) > 20:
        raise PolicyError('Unexpected merged MR response')
    matches = []
    for summary in summaries:
        iid = summary.get('iid') if isinstance(summary, dict) else None
        if type(iid) is not int or iid <= 0:
            continue
        detail = api_json(f'projects/{PROJECT_API}/merge_requests/{iid}', context['token'], opener)
        diff_refs = detail.get('diff_refs') if isinstance(detail, dict) else None
        merged_revisions = {detail.get('merge_commit_sha'), detail.get('squash_commit_sha')}
        if (detail.get('state') == 'merged' and detail.get('target_branch') == 'develop'
                and detail.get('source_project_id') == detail.get('target_project_id') == PROJECT_ID
                and context['revision'] in merged_revisions and isinstance(diff_refs, dict)
                and diff_refs.get('head_sha') == detail.get('sha')
                and re.fullmatch(r'[0-9a-f]{40}', str(detail.get('sha', '')))):
            matches.append(detail)
    if len(matches) != 1:
        raise PolicyError('Develop commit must belong to one merged dependency MR')
    return matches[0]


def verify_mr(context, opener=None):
    base = f'projects/{PROJECT_API}/merge_requests/{context["mr_iid"]}'
    mr = api_json(base, context['token'], opener)
    diff_refs = mr.get('diff_refs') if isinstance(mr, dict) else None
    if (not isinstance(mr, dict) or mr.get('state') != 'opened'
            or mr.get('target_branch') != 'develop'
            or mr.get('source_project_id') != mr.get('target_project_id')
            or mr.get('sha') != context['revision']
            or not isinstance(diff_refs, dict)
            or diff_refs.get('head_sha') != context['revision']
            or any(re.fullmatch(r'[0-9a-f]{40}', str(diff_refs.get(name, ''))) is None
                   for name in ('base_sha', 'start_sha'))):
        raise PolicyError('MR is not one open same-project MR at the approved revision')
    return mr


def review_dependency_changes(context, mr):
    base_sha = mr['diff_refs']['base_sha']
    with tempfile.TemporaryDirectory(prefix='ci271-diff-') as directory:
        repository = Path(directory) / 'repository.git'
        environment = git_environment(context['token'], directory)
        quiet = {'stdout': subprocess.DEVNULL, 'stderr': subprocess.DEVNULL,
                 'check': True, 'timeout': 120}
        subprocess.run(['/usr/bin/git', 'init', '--bare', str(repository)],
                       env=environment, **quiet)
        remote = GITLAB_URL + '/' + PROJECT_PATH + '.git'
        for revision, reference in ((base_sha, 'base'), (context['revision'], 'head')):
            subprocess.run(['/usr/bin/git', '-C', str(repository), 'fetch', '--no-tags', '--depth=1',
                            remote, revision + ':refs/review/' + reference], env=environment, **quiet)
        names = subprocess.run(
            ['/usr/bin/git', '-C', str(repository), 'diff', '--name-only', '-z',
             '--no-ext-diff', '--no-renames', 'refs/review/base', 'refs/review/head'],
            env=environment, capture_output=True, check=True, timeout=60).stdout
        if len(names) > 128 * 1024:
            raise PolicyError('MR diff is missing or too large to approve automatically')
        raw_paths = names.decode('utf-8').split('\0') if names else []
        paths = sorted(set(path for path in raw_paths if path))
        if not paths or len(paths) >= 100 or any('\n' in path or '\r' in path for path in paths):
            raise PolicyError('MR diff is missing or too large to approve automatically')
        dependency_changes = [path for path in paths if path in DEPENDENCY_FILES]
        if not dependency_changes:
            raise PolicyError('MR does not change an approved backend dependency file')
        result = subprocess.run(
            ['/usr/bin/git', '-C', str(repository), 'diff', '--unified=0', '--no-ext-diff',
             '--no-renames', 'refs/review/base', 'refs/review/head', '--', *dependency_changes],
            env=environment, capture_output=True, check=True, timeout=60)
        if len(result.stdout) > 512 * 1024:
            raise PolicyError('Dependency diff is missing or oversized')
        diff = result.stdout.decode('utf-8')

    declarations = []
    declaration = re.compile(
        r'^[+-]\s*(?:id\s|classpath\s|implementation\s|api\s|compileOnly\s|'
        r'annotationProcessor\s|runtimeOnly\s|developmentOnly\s|test[A-Za-z]*\s|'
        r'mavenCentral\s*\(|gradlePluginPortal\s*\(|maven\s*\{)')
    for line in diff.splitlines():
        if declaration.match(line) and len(line) <= 300:
            declarations.append(line)
            if len(declarations) > 200:
                raise PolicyError('Dependency declaration diff is too large')
    if not declarations:
        raise PolicyError('Dependency files changed without a reviewable declaration diff')
    return dependency_changes, declarations


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


def fetch_archive(context):
    with tempfile.TemporaryDirectory(prefix='ci271-git-') as directory:
        repository = Path(directory) / 'repository.git'
        environment = git_environment(context['token'], directory)
        quiet = {'stdout': subprocess.DEVNULL, 'stderr': subprocess.DEVNULL,
                 'check': True, 'timeout': 120}
        subprocess.run(['/usr/bin/git', 'init', '--bare', str(repository)], env=environment, **quiet)
        subprocess.run(['/usr/bin/git', '-C', str(repository), 'fetch', '--no-tags', '--depth=1',
                        GITLAB_URL + '/' + PROJECT_PATH + '.git', context['revision']],
                       env=environment, **quiet)
        archive = subprocess.run(['/usr/bin/git', '-C', str(repository), 'archive', '--format=tar',
                                  'FETCH_HEAD', 'backend'], env=environment,
                                 capture_output=True, check=True, timeout=60).stdout
    if not archive or len(archive) > 64 * 1024**2:
        raise PolicyError('Backend archive is empty or oversized')
    return archive, hashlib.sha256(archive).hexdigest()


def write_private(path, data):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as output:
        output.write(data)
        output.flush()
        os.fsync(output.fileno())


def make_stage(archive):
    stage = Path(tempfile.mkdtemp(prefix='ci222-server-', dir='/var/tmp'))
    stage.chmod(0o700)
    values = {'backend.tar': archive}
    for name in STAGE_FILES:
        path = HERE / name
        info = path.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            raise PolicyError('Installed cache automation is not trusted root-owned code')
        values['scripts/' + name] = path.read_bytes()
    manifest = {name: hashlib.sha256(data).hexdigest() for name, data in values.items()}
    for name, data in values.items():
        target = stage.joinpath(*PurePosixPath(name).parts)
        target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        write_private(target, data)
    write_private(stage / 'upload-manifest.json', json.dumps(manifest, sort_keys=True).encode())
    return stage


def stream(command, evidence):
    evidence.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    size = 0
    with evidence.open('xb') as log:
        child = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        assert child.stdout is not None
        for line in iter(child.stdout.readline, b''):
            size += len(line)
            if size > 32 * 1024**2:
                child.kill()
                raise RuntimeError('Command output exceeded 32 MiB')
            log.write(line)
            sys.stdout.buffer.write(line)
            sys.stdout.buffer.flush()
        code = child.wait(timeout=30)
    if code:
        raise subprocess.CalledProcessError(code, command)
    return evidence.read_text(errors='replace')


def test_user_command(*args):
    return ['/usr/sbin/runuser', '-u', 'feelm-ci-test', '--', '/usr/bin/env',
            'XDG_RUNTIME_DIR=/run/user/1001',
            'DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1001/bus', *map(str, args)]


def parse_path(output, marker, pattern):
    matches = re.findall(r'^' + re.escape(marker) + r'[:=]\s*(' + pattern + r')$', output, re.MULTILINE)
    if len(matches) != 1:
        raise RuntimeError('Expected one ' + marker + ' marker')
    return Path(matches[0])


def candidate_file(revision):
    return STATE / 'candidates' / (revision + '.json')


def prepare(context):
    if candidate_file(context['revision']).exists():
        prepared_candidate(context['revision'], context['mr_iid'])
        print('CACHE_CANDIDATE_REUSED=' + context['revision'])
        return
    mr = verify_mr(context)
    changes, declarations = review_dependency_changes(context, mr)
    archive, archive_sha = fetch_archive(context)
    ARCHIVES.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chown(ARCHIVES, 1001, 1001)
    archive_path = ARCHIVES / ('backend-' + context['revision'] + '-job-'
                               + str(context['job_id']) + '.tar')
    write_private(archive_path, archive)
    os.chown(archive_path, 1001, 1001)
    evidence = STATE / 'evidence' / context['revision'] / ('job-' + str(context['job_id']))
    unit = 'ci271-cache-' + uuid.uuid4().hex[:12]
    online = stream(test_user_command('/usr/bin/systemd-run', '--user', '--scope', '--unit=' + unit,
                    '/usr/bin/python3', '-I', '-B', str(HERE / 'ci-rootless-backend-check.py'), 'run',
                    '--archive', archive_path, '--revision', context['revision'], '--sha256', archive_sha,
                    '--confirm-reviewed-code', '--confirm-guard-active'), evidence / 'online.log')
    run_dir = parse_path(online, 'BACKEND_RUN_DIR', r'/home/feelm-ci-test/backend-checks/[0-9a-f]{32}')
    offline = stream(test_user_command('/usr/bin/python3', '-I', '-B',
                     str(HERE / 'ci-backend-offline-prepare.py'), 'prepare',
                     '--source-run', run_dir, '--archive', archive_path,
                     '--revision', context['revision'], '--archive-sha256', archive_sha,
                     '--confirm-cache-idle'), evidence / 'cache-copy.log')
    prepared_dir = parse_path(offline, 'PREPARED_DIR', r'/home/feelm-ci-test/offline-inputs/[0-9a-f]{32}')
    manifest_sha = digest(prepared_dir / 'manifest.json')
    stage = make_stage(archive)
    manager = stage / 'scripts/ci-server-manager-pilot.py'
    stream(['/usr/bin/python3', '-I', '-B', str(manager), 'prepare',
            '--cache-manifest', str(prepared_dir / 'manifest.json'),
            '--cache-root', str(run_dir / 'home/.gradle'),
            '--cache-manifest-sha256', manifest_sha, '--confirm-server-pilot'],
           evidence / 'stage-prepare.log')
    verified = stream(['/usr/bin/python3', '-I', '-B', str(manager), 'backend',
                       '--confirm-server-pilot'], evidence / 'offline-verify.log')
    run = parse_path(verified, 'SERVER_RUN_DIR', r'/var/tmp/ci222-server-[a-z0-9_]+/run-[a-z0-9_]+')
    summary = json.loads((run / 'lifecycle.json').read_text())
    if summary.get('passed') is not True or not (run / 'evidence/manifest.json').is_file():
        raise RuntimeError('Candidate lacks successful offline evidence')
    record = {
        'state': 'VERIFIED', 'mr_iid': context['mr_iid'], 'revision': context['revision'],
        'dependency_changes': changes, 'dependency_declarations': declarations,
        'archive_sha256': archive_sha,
        'manifest_sha256': manifest_sha, 'stage': str(stage), 'verification_run': str(run),
        'prepare_pipeline_id': context['pipeline_id'], 'prepare_job_id': context['job_id'],
    }
    write_private(candidate_file(context['revision']), json.dumps(record, indent=2).encode())
    print('CACHE_CANDIDATE_VERIFIED=' + context['revision'])
    print('CACHE_DEPENDENCY_DECLARATIONS=' + json.dumps(declarations))
    print('CACHE_MANIFEST_SHA256=' + manifest_sha)
    print('CANDIDATE_STAGE=' + str(stage))


def read_candidate(context, expected_state):
    path = candidate_file(context['revision'])
    if not path.exists() or path.is_symlink():
        raise PolicyError('Verified cache candidate is missing for this revision')
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077:
        raise PolicyError('Candidate record is not trusted root-owned state')
    value = json.loads(path.read_text())
    states = {expected_state} if isinstance(expected_state, str) else set(expected_state)
    if value.get('state') not in states or value.get('mr_iid') != context['mr_iid']:
        raise PolicyError('Candidate state or MR does not match the requested action')
    return path, value


def prepared_candidate(revision, mr_iid):
    context = {'revision': revision, 'mr_iid': mr_iid}
    _, candidate = read_candidate(context, {'VERIFIED', 'PROMOTED'})
    pilot = load('ci294_candidate_pilot', 'ci-server-manager-pilot.py')
    stage = Path(candidate['stage'])
    pilot.check_base(stage)
    prepared = json.loads((stage / 'prepared.json').read_text())
    if (prepared.get('source_revision') != revision
            or prepared.get('cache_manifest_sha256') != candidate.get('manifest_sha256')):
        raise PolicyError('Prepared stage identity does not match verified candidate')
    print('CACHE_SELECTED_REVISION=' + revision)
    print('CACHE_SELECTED_STAGE=' + str(stage))
    return stage


def automatic_prepare(context):
    expected = {'action', 'project_id', 'pipeline_id', 'job_id', 'mr_iid', 'revision', 'token'}
    if (set(context) != expected or context.get('action') != 'prepare'
            or context.get('project_id') != PROJECT_ID
            or any(type(context.get(name)) is not int or context[name] <= 0
                   for name in ('pipeline_id', 'job_id', 'mr_iid'))
            or re.fullmatch(r'[0-9a-f]{40}', str(context.get('revision', ''))) is None
            or not isinstance(context.get('token'), str)
            or not 16 <= len(context['token']) <= 4096
            or any(character.isspace() for character in context['token'])):
        raise PolicyError('Automatic cache preparation context is invalid')
    if fcntl is None or not hasattr(os, 'geteuid') or os.geteuid() != 0:
        raise PolicyError('Automatic cache preparation requires the trusted root adapter')
    LOCK.parent.mkdir(parents=True, exist_ok=True)
    with LOCK.open('a+b') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        prepare(context)


def atomic_config(value):
    temporary = ADAPTER_CONFIG.with_name(ADAPTER_CONFIG.name + '.ci271-' + uuid.uuid4().hex)
    write_private(temporary, (json.dumps(value, sort_keys=True) + '\n').encode())
    os.replace(temporary, ADAPTER_CONFIG)


def active_ci_units():
    result = subprocess.run(['systemctl', 'list-units', '--state=running', '--no-legend',
                             'ci222-owner-*.service', 'ci222-server-*.service'],
                            capture_output=True, text=True, check=True, timeout=30)
    return [line.split()[0] for line in result.stdout.splitlines() if line.strip()]


def switch_stage(old_config, new_stage):
    if active_ci_units():
        raise PolicyError('MR test units are still running')
    subprocess.run(['systemctl', 'stop', RUNNER_SERVICE], check=True, timeout=120)
    replacement = dict(old_config)
    replacement['prepared_base'] = new_stage
    atomic_config(replacement)
    try:
        subprocess.run(['systemctl', 'start', RUNNER_SERVICE], check=True, timeout=120)
        subprocess.run(['systemctl', 'is-active', '--quiet', RUNNER_SERVICE], check=True, timeout=30)
    except Exception:
        atomic_config(old_config)
        subprocess.run(['systemctl', 'start', RUNNER_SERVICE], check=False, timeout=120)
        raise


def promote(context):
    mr = merged_mr(context)
    candidate_context = dict(context, mr_iid=mr['iid'], revision=mr['sha'])
    path, candidate = read_candidate(candidate_context, 'VERIFIED')
    pilot = load('ci271_server_manager', 'ci-server-manager-pilot.py')
    stage = Path(candidate['stage'])
    pilot.check_base(stage)
    prepared = json.loads((stage / 'prepared.json').read_text())
    if (prepared.get('source_revision') != candidate_context['revision']
            or prepared.get('cache_manifest_sha256') != candidate['manifest_sha256']):
        raise PolicyError('Prepared stage identity does not match verified candidate')
    old = json.loads(ADAPTER_CONFIG.read_text())
    if set(old) != {'gitlab_url', 'project_path', 'prepared_base'}:
        raise PolicyError('Unexpected adapter configuration')
    switch_stage(old, str(stage))
    promoted = dict(candidate, state='PROMOTED', previous_stage=old['prepared_base'],
                    merged_revision=context['revision'],
                    promote_pipeline_id=context['pipeline_id'], promote_job_id=context['job_id'])
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.new')
    try:
        write_private(temporary, json.dumps(promoted, indent=2).encode())
        os.replace(temporary, path)
    except Exception:
        current = dict(old, prepared_base=str(stage))
        switch_stage(current, old['prepared_base'])
        raise
    print('CACHE_PROMOTED=' + candidate_context['revision'])
    print('CACHE_MERGED_REVISION=' + context['revision'])
    print('PREVIOUS_STAGE=' + old['prepared_base'])
    print('CURRENT_STAGE=' + str(stage))


def rollback(context):
    path, candidate = read_candidate(context, 'PROMOTED')
    current = json.loads(ADAPTER_CONFIG.read_text())
    if current.get('prepared_base') != candidate['stage']:
        raise PolicyError('Active stage no longer matches this promotion')
    switch_stage(current, candidate['previous_stage'])
    candidate.update(state='ROLLED_BACK', rollback_pipeline_id=context['pipeline_id'],
                     rollback_job_id=context['job_id'])
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.new')
    write_private(temporary, json.dumps(candidate, indent=2).encode())
    os.replace(temporary, path)
    print('CACHE_ROLLED_BACK=' + context['revision'])
    print('CURRENT_STAGE=' + candidate['previous_stage'])


def main():
    if fcntl is None or not hasattr(os, 'geteuid') or os.geteuid() != 0 or sys.argv[1:]:
        print('CACHE_AUTOMATION_REJECTED: root stdin-only entry point required', file=sys.stderr)
        return 2
    context = read_context()
    actor = verify_job(context)
    print('CACHE_ACTOR=' + actor)
    LOCK.parent.mkdir(parents=True, exist_ok=True)
    with LOCK.open('a+b') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        {'promote': promote, 'rollback': rollback}[context['action']](context)
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (PolicyError, ValueError, OSError, subprocess.SubprocessError,
            urllib.error.URLError, json.JSONDecodeError) as exc:
        print('CACHE_AUTOMATION_FAILED: ' + type(exc).__name__ + ': ' + str(exc), file=sys.stderr)
        raise SystemExit(2)
