#!/usr/bin/env python3
"""고정된 Claude 구독 요청 하나만 보내며 CLI 원문과 인증 정보를 기록하지 않는다."""
import json
import os
from pathlib import Path
import subprocess
import tempfile

CLAUDE = '/home/feelm-ci-review/.local/bin/claude'
PROMPT = 'Reply with exactly OK. Do not use tools or read files.'


def context_valid(env):
    return (env.get('CI_COMMIT_BRANCH') == 'develop'
            and env.get('CI_COMMIT_REF_PROTECTED') == 'true'
            and env.get('CI_ENVIRONMENT_NAME') == 'claude-review'
            and env.get('CI_PIPELINE_SOURCE') in ('push', 'web')
            and env.get('CI_DEBUG_TRACE', '').lower() not in ('true', '1')
            and env.get('CI_DEBUG_SERVICES', '').lower() not in ('true', '1'))


def child_environment(token, home):
    # API key, GitLab 인증 정보, proxy와 프로젝트 환경변수는 전달하지 않는다.
    return {'HOME': str(home), 'CLAUDE_CONFIG_DIR': str(home / '.claude'),
            'PATH': '/usr/local/bin:/usr/bin:/bin', 'LANG': 'C.UTF-8',
            'CLAUDE_CODE_OAUTH_TOKEN': token, 'DISABLE_AUTOUPDATER': '1',
            'CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC': '1'}


def command():
    return [CLAUDE, '-p', '--output-format', 'json', '--max-turns', '1',
            '--tools', '', '--disable-slash-commands', '--setting-sources', '',
            '--strict-mcp-config', '--mcp-config', '{"mcpServers":{}}',
            '--settings', '{"disableAllHooks":true}', '--no-session-persistence']


def classify(result):
    try:
        data = json.loads(result.stdout)
    except (ValueError, TypeError):
        data = None
    if (result.returncode == 0 and isinstance(data, dict)
            and data.get('type') == 'result' and data.get('subtype') == 'success'
            and data.get('is_error') is False and isinstance(data.get('result'), str)
            and data['result'].strip() == 'OK'):
        return 'PASS'
    # 휴리스틱은 진단 분류에만 사용하며 원문 응답은 출력하지 않는다.
    text = (str(result.stdout) + str(result.stderr)).lower()
    if any(word in text for word in ['rate_limit', 'rate limit', 'usage limit', '429']):
        return 'USAGE_LIMIT'
    if any(word in text for word in ['unauthorized', 'authentication', 'invalid token', '401', 'login expired']):
        return 'AUTHENTICATION_FAILED'
    return 'REQUEST_FAILED_OR_UNEXPECTED_RESPONSE'


def main():
    if not context_valid(os.environ):
        print('CLAUDE_AUTH_CHECK: REJECTED_CI_CONTEXT')
        return 1
    token = os.environ.get('CLAUDE_CODE_OAUTH_TOKEN', '')
    if not token or any(char.isspace() for char in token):
        print('CLAUDE_AUTH_CHECK: MISSING_OR_INVALID_TOKEN')
        return 1
    with tempfile.TemporaryDirectory(prefix='feelm-claude-auth-') as directory:
        home = Path(directory)
        try:
            # 빈 작업·홈 디렉터리로 저장소 에이전트 설정과 사용자 로그인 상태를 읽지 않는다.
            result = subprocess.run(command(), input=PROMPT, text=True, capture_output=True,
                                    cwd=home, env=child_environment(token, home), timeout=120)
            status = classify(result)
        except subprocess.TimeoutExpired:
            status = 'TIMEOUT'
        except OSError:
            status = 'CLI_EXECUTION_FAILED'
    print('CLAUDE_AUTH_CHECK: ' + status)
    return 0 if status == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
