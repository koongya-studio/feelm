#!/usr/bin/env python3
"""Emit bounded CI context for the installed dependency-cache manager."""
import json
import os
import re
import sys


def context(environment=None):
    env = environment or os.environ
    required = {
        'CI_PIPELINE_SOURCE': 'web',
        'CI_COMMIT_BRANCH': 'develop',
        'CI_COMMIT_REF_PROTECTED': 'true',
        'CI_PROJECT_ID': '1441379',
        'CI_PROJECT_PATH': 's15-bigdata-dist-sub1/S15P21E106',
    }
    if any(env.get(name) != value for name, value in required.items()):
        raise ValueError('Dependency-cache actions require a protected develop web pipeline')
    action = env.get('CACHE_ACTION')
    if action not in {'prepare', 'promote', 'rollback'}:
        raise ValueError('Unsupported cache action')
    iid = env.get('CACHE_MR_IID', '')
    revision = env.get('CACHE_COMMIT_SHA', '')
    token = env.get('CI_JOB_TOKEN', '')
    if not re.fullmatch(r'[1-9][0-9]{0,9}', iid):
        raise ValueError('CACHE_MR_IID must be a positive number')
    if not re.fullmatch(r'[0-9a-f]{40}', revision):
        raise ValueError('CACHE_COMMIT_SHA must be the full MR commit SHA')
    if not 16 <= len(token) <= 4096 or any(character.isspace() for character in token):
        raise ValueError('Missing or invalid CI job token')
    identities = {
        'project_id': int(env.get('CI_PROJECT_ID', '0')),
        'pipeline_id': int(env.get('CI_PIPELINE_ID', '0')),
        'job_id': int(env.get('CI_JOB_ID', '0')),
    }
    if any(value <= 0 for value in identities.values()):
        raise ValueError('Invalid GitLab job identity')
    return {
        'action': action,
        **identities,
        'mr_iid': int(iid),
        'revision': revision,
        'token': token,
    }


def main():
    try:
        print(json.dumps(context(), separators=(',', ':')))
        return 0
    except (ValueError, TypeError) as exc:
        print('CACHE_CONTEXT_REJECTED: ' + str(exc), file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
