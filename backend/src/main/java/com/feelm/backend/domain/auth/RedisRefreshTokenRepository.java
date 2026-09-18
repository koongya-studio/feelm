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
 * <p>키를 사용자 단위로 잡으면 재사용 감지 시의 전량 폐기가 키 삭제로 끝난다. Set에는 TTL을 발급할 때마다 다시 걸어 두므로, 활동이 없는 사용자의 키는
 * Refresh Token 수명이 지나면 스스로 사라진다.
 *
 * <p>회전하면 소비한 식별자를 사용자별 유예 Hash에 남긴다. 필드는 "소비된 식별자", 값은 "그것을 대체한 식별자와 유예 만료 시각"이다. 같은 토큰이 유예 안에 다시
 * 들어왔을 때 탈취가 아니라 중복 도착으로 판정하는 근거이며, 회전이 이어졌으면 링크를 따라가 <em>현재</em> 세션 식별자까지 도달한다. 한 세대만 보면 유예 안에 회전이
 * 한 번 더 일어난 것만으로 정상 중복이 탈취로 뒤집힌다(리뷰 A-2·B-2).
 *
 * <p>유예를 사용자별 키 하나로 모은 이유는 링크를 따라가려면 한 번의 조회로 계보를 볼 수 있어야 하고, 키 수가 세션 수에 비례해 늘지 않아야 하기 때문이다. 링크를
 * 따라가다 <em>막다른 끝</em>에 이르면 그 계보는 회전이 아니라 폐기로 끝난 것이므로(로그아웃·비밀번호 변경·탈퇴) 탈취로 보지 않고 거절만 한다. 이 구분이 없으면
 * 폐기 직후 다시 로그인한 새 세션이 뒤늦게 도착한 옛 중복 하나에 끊긴다(리뷰 B-3).
 *
 * <p>Hash 자체의 TTL은 회전할 때마다 다시 걸리므로 필드의 수명은 값에 적은 만료 시각으로 판단한다. 키 TTL에만 기대면 활동이 이어지는 동안 오래된 링크가 함께
 * 연장돼 유예 창이 사실상 무한해진다.
 */
@Repository
public class RedisRefreshTokenRepository implements RefreshTokenRepository {

    private static final String KEY_PREFIX = "auth:refresh:";
    private static final String GRACE_KEY_PREFIX = KEY_PREFIX + "grace:";

    private static final String ROTATED_PREFIX = "ROTATED:";
    private static final String REPLAYED_PREFIX = "REPLAYED:";

    /**
     * 따라갈 유예 링크의 최대 수.
     *
     * <p>유예 창(기본 10초) 안에 회전이 이만큼 이어지는 것은 정상 사용에서 나오지 않는다. 한도를 두는 것은 값이 망가져 링크가 고리를 이뤄도 스크립트가 끝나게 하기
     * 위해서다.
     */
    private static final int MAX_GRACE_HOPS = 10;

    /**
     * 회전 한 번을 원자적으로 판정한다.
     *
     * <p>{@code KEYS[1]}은 사용자의 토큰 Set, {@code KEYS[2]}는 사용자의 유예 Hash다. {@code ARGV}는 순서대로 현재 토큰
     * 식별자, 새 토큰 식별자, Set의 TTL(ms), 유예 시간(ms), 최대 링크 수다.
     *
     * <p>확인·삭제·등록을 나눠 실행하면 같은 토큰을 든 두 요청이 동시에 도착했을 때 양쪽 모두 통과하거나, 전량 폐기 뒤에 새 토큰이 저장될 수 있으므로 한 번에
     * 처리한다.
     */
    private static final DefaultRedisScript<String> ROTATE_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    local clock = redis.call('TIME')
                    local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
                    local grace = tonumber(ARGV[4])

                    if redis.call('SREM', KEYS[1], ARGV[1]) == 1 then
                      redis.call('SADD', KEYS[1], ARGV[2])
                      redis.call('PEXPIRE', KEYS[1], ARGV[3])
                      if grace > 0 then
                        redis.call('HSET', KEYS[2], ARGV[1], ARGV[2] .. ':' .. (now + grace))
                        redis.call('PEXPIRE', KEYS[2], grace)
                      end
                      return 'ROTATED:' .. ARGV[2]
                    end

                    if redis.call('EXISTS', KEYS[1]) == 0 then
                      return 'UNKNOWN_SESSION'
                    end

                    local node = ARGV[1]
                    local fresh = true
                    local revoked = false
                    for _ = 1, tonumber(ARGV[5]) do
                      local link = redis.call('HGET', KEYS[2], node)
                      if not link then
                        revoked = node ~= ARGV[1]
                        break
                      end
                      local mark = string.find(link, ':')
                      local successor = string.sub(link, 1, mark - 1)
                      fresh = fresh and now <= tonumber(string.sub(link, mark + 1))
                      if redis.call('SISMEMBER', KEYS[1], successor) == 1 then
                        if fresh then
                          redis.call('PEXPIRE', KEYS[1], ARGV[3])
                          return 'REPLAYED:' .. successor
                        end
                        break
                      end
                      node = successor
                    end

                    if revoked then
                      return 'REVOKED_SESSION'
                    end

                    redis.call('DEL', KEYS[1], KEYS[2])
                    return 'REUSED'
                    """,
                    String.class);

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;
    private final Duration rotationGrace;

    public RedisRefreshTokenRepository(
            StringRedisTemplate redisTemplate, JwtProperties properties) {
        this.redisTemplate = redisTemplate;
        this.ttl = properties.refreshTokenTtl();
        this.rotationGrace = properties.refreshRotationGrace();
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
    public Rotation rotate(long userId, String currentTokenId, String newTokenId) {
        String result =
                redisTemplate.execute(
                        ROTATE_SCRIPT,
                        List.of(key(userId), graceKey(userId)),
                        currentTokenId,
                        newTokenId,
                        Long.toString(ttl.toMillis()),
                        Long.toString(rotationGrace.toMillis()),
                        Integer.toString(MAX_GRACE_HOPS));

        if (result == null) {
            throw new IllegalStateException("회전 스크립트가 결과를 돌려주지 않았습니다.");
        }
        if (result.startsWith(ROTATED_PREFIX)) {
            return Rotation.rotated(result.substring(ROTATED_PREFIX.length()));
        }
        if (result.startsWith(REPLAYED_PREFIX)) {
            return Rotation.replayed(result.substring(REPLAYED_PREFIX.length()));
        }
        for (Outcome rejection : Outcome.values()) {
            if (rejection.name().equals(result)) {
                return Rotation.rejected(rejection);
            }
        }

        /*
         * 스크립트와 이 코드가 어긋난 것이지 사용자의 세션에 대한 판정이 아니다. 거절로 바꾸면 멀쩡한 세션이 끊기고 원인도 로그에서 사라진다(리뷰 A-5).
         * 500으로 드러내면 프런트가 서버 사정으로 보고 세션을 유지한다.
         */
        throw new IllegalStateException("회전 스크립트가 알 수 없는 결과를 돌려줬습니다: " + result);
    }

    /**
     * 토큰 Set만 지우고 유예 Hash는 두어 짧은 시간 뒤 스스로 사라지게 한다.
     *
     * <p>유예 Hash를 함께 지우면 "이 계보는 폐기로 끝났다"는 증거까지 사라진다. 그러면 폐기 직후 다시 로그인한 사용자에게 뒤늦게 도착한 옛 중복이 단순 재사용으로
     * 보여, 방금 만든 새 세션이 탈취 대응에 끊긴다(리뷰 B-3). 남겨 두어도 링크는 Set에 있는 식별자만 가리키므로 폐기된 세션을 되살리지 못한다.
     */
    @Override
    public void deleteAll(long userId) {
        redisTemplate.delete(key(userId));
    }

    private String key(long userId) {
        return KEY_PREFIX + userId;
    }

    private String graceKey(long userId) {
        return GRACE_KEY_PREFIX + userId;
    }
}
