package com.feelm.backend.domain.auth;

import com.feelm.backend.global.security.JwtProperties;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

/**
 * 사용자별 Set 하나에 유효한 토큰 식별자를 모아 두는 Redis 구현이다.
 *
 * <p>키를 사용자 단위로 잡으면 재사용 감지 시의 전량 폐기가 키 하나 삭제로 끝난다. Set에는 TTL을 발급할 때마다 다시 걸어 두므로, 활동이 없는 사용자의 키는
 * Refresh Token 수명이 지나면 스스로 사라진다.
 */
@Repository
public class RedisRefreshTokenRepository implements RefreshTokenRepository {

    private static final String KEY_PREFIX = "auth:refresh:";
    private static final DefaultRedisScript<Long> ROTATE_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('SREM', KEYS[1], ARGV[1]) == 1 then "
                            + "redis.call('SADD', KEYS[1], ARGV[2]); "
                            + "redis.call('PEXPIRE', KEYS[1], ARGV[3]); "
                            + "return 1; "
                            + "else redis.call('DEL', KEYS[1]); return 0; end",
                    Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public RedisRefreshTokenRepository(
            StringRedisTemplate redisTemplate, JwtProperties properties) {
        this.redisTemplate = redisTemplate;
        this.ttl = properties.refreshTokenTtl();
    }

    @Override
    public void save(long userId, String tokenId) {
        String key = key(userId);
        redisTemplate.opsForSet().add(key, tokenId);
        redisTemplate.expire(key, ttl);
    }

    @Override
    public boolean exists(long userId, String tokenId) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(key(userId), tokenId));
    }

    @Override
    public boolean deleteIfPresent(long userId, String tokenId) {
        Long removed = redisTemplate.opsForSet().remove(key(userId), tokenId);
        return removed != null && removed > 0;
    }

    @Override
    public boolean rotate(long userId, String currentTokenId, String newTokenId) {
        Long rotated =
                redisTemplate.execute(
                        ROTATE_SCRIPT,
                        List.of(key(userId)),
                        currentTokenId,
                        newTokenId,
                        Long.toString(ttl.toMillis()));
        return rotated != null && rotated == 1L;
    }

    @Override
    public void deleteAll(long userId) {
        redisTemplate.delete(key(userId));
    }

    private String key(long userId) {
        return KEY_PREFIX + userId;
    }
}
