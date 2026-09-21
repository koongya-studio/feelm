package com.feelm.backend.infrastructure.redis;

import com.feelm.backend.domain.trend.model.TrendingSnapshot;
import com.feelm.backend.domain.trend.model.TrendingSnapshot.RankedMovie;
import com.feelm.backend.domain.trend.repository.TrendingSnapshotReader;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Spark가 게시한 급상승 스냅샷을 읽는다(스트리밍 계약 5.1·5.2).
 *
 * <p>최신 결과 {@code feelm:trending:current}(TTL 15분)를 먼저 읽고, 없으면 만료가 없는 {@code
 * feelm:trending:last-good}을 읽어 {@code stale}로 표시한다. 두 키가 모두 없으면 아직 게시된 결과가 없다는 뜻이므로 빈 값을 돌려주고 호출자가
 * 초기 고정 목록을 쓴다. 조회가 실패하거나 저장된 값이 손상돼 게시된 결과를 알 수 없으면 {@code TREND_UNAVAILABLE}이다. 값이 있는데 초기 목록으로
 * 되돌리면 이미 보여 준 급상승이 사라지므로 오류로 알린다.
 *
 * <p>상태를 들고 있지 않으므로 애플리케이션을 재시작해도 마지막 유효 결과는 Redis에 남은 그대로 제공된다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RedisTrendingSnapshotReader implements TrendingSnapshotReader {

    private static final String CURRENT_KEY = "feelm:trending:current";
    private static final String LAST_GOOD_KEY = "feelm:trending:last-good";
    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    /** 게시 편수 상한. 스트리밍 계약 5.2의 "최대 30편"이며 API의 {@code size} 상한과는 별개 값이다. */
    private static final int MAX_PUBLISHED_ITEMS = 30;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<TrendingSnapshot> read() {
        // 최신 결과가 쓸 수 있으면 마지막 유효 결과는 읽지 않는다. 읽어 두면 쓰지도 않을 키의 조회 실패가
        // 이미 손에 쥔 정상 결과를 503으로 바꾼다(리뷰 A-F1·B-F2).
        String current = get(CURRENT_KEY);
        Optional<TrendingSnapshot> fresh = parse(CURRENT_KEY, current, false);
        if (fresh.isPresent()) {
            return fresh;
        }
        String lastGood = get(LAST_GOOD_KEY);
        Optional<TrendingSnapshot> retained = parse(LAST_GOOD_KEY, lastGood, true);
        if (retained.isPresent()) {
            return retained;
        }
        if (!hasValue(current) && !hasValue(lastGood)) {
            return Optional.empty();
        }
        throw new BusinessException(ErrorCode.TREND_UNAVAILABLE);
    }

    private String get(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (RuntimeException failure) {
            // 이 503은 Redis 장애라는 사실 말고는 응답에 남지 않으므로 원인을 로그로 남긴다(리뷰 A-F3·B-F5).
            log.warn("급상승 스냅샷을 조회하지 못했습니다. key={}, cause={}", key, failure.toString());
            BusinessException unavailable = new BusinessException(ErrorCode.TREND_UNAVAILABLE);
            unavailable.initCause(failure);
            throw unavailable;
        }
    }

    private Optional<TrendingSnapshot> parse(String key, String stored, boolean stale) {
        if (!hasValue(stored)) {
            return Optional.empty();
        }
        try {
            return Optional.of(snapshot(stored, stale));
        } catch (RuntimeException invalid) {
            // 공개 경로라 요청마다 찍힌다. 스택트레이스 대신 원인 메시지만 남기고 스냅샷 값은 남기지 않는다.
            log.warn("급상승 스냅샷을 사용할 수 없습니다. key={}, reason={}", key, invalid.getMessage());
            return Optional.empty();
        }
    }

    private TrendingSnapshot snapshot(String stored, boolean stale) {
        JsonNode root = objectMapper.readTree(stored);
        JsonNode items = root.path("items");
        if (!root.isObject()
                || !root.path("schemaVersion").isIntegralNumber()
                || root.path("schemaVersion").intValue() != SUPPORTED_SCHEMA_VERSION
                || !items.isArray()
                || items.isEmpty()
                || items.size() > MAX_PUBLISHED_ITEMS) {
            throw new IllegalArgumentException("Invalid trending snapshot metadata");
        }
        Instant from = instant(root.path("period").path("from"));
        Instant to = instant(root.path("period").path("to"));
        Instant updatedAt = instant(root.path("updatedAt"));

        List<RankedMovie> ranked = new ArrayList<>(items.size());
        Set<Long> movieIds = new HashSet<>();
        Set<Integer> ranks = new HashSet<>();
        for (JsonNode item : items) {
            ranked.add(ranked(item, movieIds, ranks));
        }
        return new TrendingSnapshot(from, to, updatedAt, stale, List.copyOf(ranked));
    }

    private RankedMovie ranked(JsonNode item, Set<Long> movieIds, Set<Integer> ranks) {
        JsonNode movieId = item.path("movieId");
        JsonNode rank = item.path("rank");
        JsonNode rankDelta = item.path("rankDelta");
        JsonNode trendScore = item.path("trendScore");
        // rank 상한은 검사하지 않는다. 표시용 값이고, 게시 편수 정책이 바뀌어도 전면 503이 되지 않아야 한다.
        if (!movieId.isIntegralNumber()
                || !movieId.canConvertToLong()
                || movieId.longValue() <= 0
                || !movieIds.add(movieId.longValue())
                || !rank.isIntegralNumber()
                || !rank.canConvertToInt()
                || rank.intValue() <= 0
                || !ranks.add(rank.intValue())
                || !(rankDelta.isNull()
                        || (rankDelta.isIntegralNumber() && rankDelta.canConvertToInt()))
                || !trendScore.isNumber()) {
            throw new IllegalArgumentException("Invalid trending snapshot item");
        }
        BigDecimal score = trendScore.decimalValue();
        if (score.signum() <= 0 || score.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Invalid trending score");
        }
        return new RankedMovie(
                movieId.longValue(),
                rank.intValue(),
                rankDelta.isNull() ? null : rankDelta.intValue(),
                score);
    }

    private Instant instant(JsonNode node) {
        // 계약의 시각은 UTC 표기다. 오프셋 표기는 게시 형식이 아니므로 손상으로 본다.
        if (!node.isTextual() || !node.asString().endsWith("Z")) {
            throw new IllegalArgumentException("Invalid trending snapshot timestamp");
        }
        try {
            return Instant.parse(node.asString());
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("Invalid trending snapshot timestamp", invalid);
        }
    }

    private boolean hasValue(String stored) {
        return stored != null && !stored.isBlank();
    }
}
