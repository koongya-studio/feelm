package com.feelm.backend.support;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 공유 Redis를 사용하는 테스트 사이의 키 격리를 제공한다. */
public final class SharedRedisState {

    private SharedRedisState() {}

    public static void clear(StringRedisTemplate redisTemplate) {
        try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
    }
}
