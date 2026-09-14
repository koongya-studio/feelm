package com.feelm.backend.support;

import com.redis.testcontainers.RedisContainer;

/** 테스트 JVM 동안 하나의 Redis 서버를 공유한다. */
public final class SharedRedisContainer {

    private static final RedisContainer REDIS = startRedis();

    private SharedRedisContainer() {}

    public static RedisContainer instance() {
        return REDIS;
    }

    private static RedisContainer startRedis() {
        RedisContainer redis = new RedisContainer("redis:8-alpine");
        redis.start();
        return redis;
    }
}
