#!/bin/sh
set -eu

: "${REDIS_PASSWORD:?REDIS_PASSWORD is required}"

# Hash credentials before writing ACL syntax: passwords cannot inject ACL rules.
password_hash() {
    printf '%s' "$1" | sha256sum | cut -d ' ' -f 1
}

umask 077
acl_file=/tmp/feelm-redis-users.acl
{
    printf 'user default on #%s ~* &* +@all\n' "$(password_hash "$REDIS_PASSWORD")"
    if [ -n "${REDIS_READONLY_PASSWORD:-}" ]; then
        if [ "$REDIS_READONLY_PASSWORD" = "$REDIS_PASSWORD" ]; then
            echo 'Redis read-only and application passwords must differ' >&2
            exit 1
        fi
        printf 'user feelm_readonly on #%s ' "$(password_hash "$REDIS_READONLY_PASSWORD")"
        cat /usr/local/etc/redis/readonly.acl
    else
        printf 'user feelm_readonly off resetpass -@all resetkeys resetchannels\n'
    fi
    if [ -n "${REDIS_LOCAL_BACKEND_PASSWORD:-}" ]; then
        if [ "$REDIS_LOCAL_BACKEND_PASSWORD" = "$REDIS_PASSWORD" ]; then
            echo 'Redis local-backend password must differ from other Redis passwords' >&2
            exit 1
        fi
        if [ -n "${REDIS_READONLY_PASSWORD:-}" ] \
            && [ "$REDIS_LOCAL_BACKEND_PASSWORD" = "$REDIS_READONLY_PASSWORD" ]; then
            echo 'Redis local-backend password must differ from other Redis passwords' >&2
            exit 1
        fi
        printf 'user feelm_local_backend on #%s ' "$(password_hash "$REDIS_LOCAL_BACKEND_PASSWORD")"
        cat /usr/local/etc/redis/local-backend.acl
    else
        printf 'user feelm_local_backend off resetpass -@all resetkeys resetchannels\n'
    fi
} > "$acl_file"
chown redis:redis "$acl_file"

# Re-enter the official entrypoint so Redis runs as its unprivileged user.
exec /usr/local/bin/docker-entrypoint.sh redis-server \
    --aclfile "$acl_file" \
    --appendonly yes \
    --appendfsync everysec \
    --maxmemory 512mb \
    --maxmemory-policy noeviction
