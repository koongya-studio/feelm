package com.feelm.backend.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.trend.model.TrendingSnapshot;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.support.SharedPostgresContractTest;
import com.feelm.backend.support.SharedRedisState;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

/**
 * Spark가 게시하는 급상승 스냅샷 키(스트리밍 계약 5.1·5.2)를 실제 Redis에서 읽어 A→B 표시 정책의 입력을 고정한다.
 *
 * <p>최신 결과가 만료된 뒤에도 마지막 유효 결과를 유지하는지, 손상·조회 장애를 초기 목록으로 되돌리지 않고 오류로 알리는지 확인한다.
 */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
class RedisTrendingSnapshotReaderTest extends SharedPostgresContractTest {

    private static final String CURRENT_KEY = "feelm:trending:current";
    private static final String LAST_GOOD_KEY = "feelm:trending:last-good";
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void clearSnapshots() {
        SharedRedisState.clear(redisTemplate);
    }

    @Test
    void readsLatestSnapshotAsFresh() {
        redisTemplate
                .opsForValue()
                .set(CURRENT_KEY, snapshot("2026-09-18T12:00:00Z", item(550, 1, "null", "1.0")));
        redisTemplate
                .opsForValue()
                .set(LAST_GOOD_KEY, snapshot("2026-09-18T11:55:00Z", item(551, 1, "null", "1.0")));

        TrendingSnapshot snapshot = reader().read().orElseThrow();

        assertThat(snapshot.stale()).isFalse();
        assertThat(snapshot.updatedAt()).isEqualTo(Instant.parse("2026-09-18T12:00:00Z"));
        assertThat(snapshot.periodFrom()).isEqualTo(Instant.parse("2026-09-18T11:00:00Z"));
        assertThat(snapshot.periodTo()).isEqualTo(Instant.parse("2026-09-18T12:00:00Z"));
        assertThat(snapshot.items()).hasSize(1);
        assertThat(snapshot.items().getFirst().movieId()).isEqualTo(550);
        assertThat(snapshot.items().getFirst().rankDelta()).isNull();
        assertThat(snapshot.items().getFirst().trendScore()).isEqualByComparingTo("1.0");
    }

    @Test
    void readsRetainedSnapshotOnFreshInstance() {
        // current는 15분 TTL로 사라지고 last-good만 남는 상태다. 2시간이 지나도 이 결과를 유지한다.
        redisTemplate
                .opsForValue()
                .set(LAST_GOOD_KEY, snapshot("2026-09-18T09:00:00Z", item(550, 1, "2", "0.5")));

        for (int restart = 0; restart < 2; restart++) {
            TrendingSnapshot snapshot = reader().read().orElseThrow();

            assertThat(snapshot.stale()).isTrue();
            assertThat(snapshot.updatedAt()).isEqualTo(Instant.parse("2026-09-18T09:00:00Z"));
            assertThat(snapshot.items().getFirst().rankDelta()).isEqualTo(2);
        }
    }

    @Test
    void fallsBackToRetainedSnapshotWhenLatestIsCorrupt() {
        redisTemplate.opsForValue().set(CURRENT_KEY, "{\"schemaVersion\":1,\"items\":[]}");
        redisTemplate
                .opsForValue()
                .set(LAST_GOOD_KEY, snapshot("2026-09-18T09:00:00Z", item(550, 1, "null", "1.0")));

        TrendingSnapshot snapshot = reader().read().orElseThrow();

        assertThat(snapshot.stale()).isTrue();
        assertThat(snapshot.updatedAt()).isEqualTo(Instant.parse("2026-09-18T09:00:00Z"));
    }

    @Test
    void returnsEmptyWhenNothingWasPublished() {
        assertThat(reader().read()).isEmpty();
    }

    @Test
    void reportsUnavailableWhenPublishedSnapshotIsCorrupt() {
        List<String> corrupted =
                List.of(
                        "not json",
                        "[]",
                        snapshot("2026-09-18T12:00:00Z", item(550, 1, "null", "1.0"))
                                .replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                        "{\"schemaVersion\":1,\"period\":{\"from\":\"2026-09-18T11:00:00Z\","
                                + "\"to\":\"2026-09-18T12:00:00Z\"},"
                                + "\"updatedAt\":\"2026-09-18T12:00:00Z\",\"items\":[]}",
                        snapshot("2026-09-18T21:00:00+09:00", item(550, 1, "null", "1.0")),
                        snapshot("2026-09-18T12:00:00Z", item(0, 1, "null", "1.0")),
                        snapshot("2026-09-18T12:00:00Z", item(550, 0, "null", "1.0")),
                        snapshot("2026-09-18T12:00:00Z", item(550, 1, "null", "0")),
                        snapshot("2026-09-18T12:00:00Z", item(550, 1, "null", "1.5")),
                        snapshot("2026-09-18T12:00:00Z", item(550, 1, "\"1\"", "1.0")),
                        snapshot(
                                "2026-09-18T12:00:00Z",
                                item(550, 1, "null", "1.0") + "," + item(550, 2, "null", "0.5")),
                        snapshot(
                                "2026-09-18T12:00:00Z",
                                item(550, 1, "null", "1.0") + "," + item(551, 1, "null", "0.5")));

        for (String payload : corrupted) {
            SharedRedisState.clear(redisTemplate);
            redisTemplate.opsForValue().set(CURRENT_KEY, payload);

            assertThatThrownBy(() -> reader().read())
                    .as("corrupt payload must not fall back to the initial list: %s", payload)
                    .isInstanceOf(BusinessException.class)
                    .extracting(failure -> ((BusinessException) failure).getErrorCode())
                    .isEqualTo(ErrorCode.TREND_UNAVAILABLE);
        }
    }

    @Test
    void keepsLatestSnapshotWhenRetainedKeyWouldFail() {
        // 최신 결과를 이미 읽었으면 마지막 유효 결과는 쓰이지 않는다. 그 키의 조회 실패가 정상 응답을
        // 503으로 바꾸지 않아야 한다(리뷰 A-F1·B-F2).
        StringRedisTemplate partiallyFailing = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(partiallyFailing.opsForValue()).thenReturn(values);
        when(values.get(CURRENT_KEY))
                .thenReturn(snapshot("2026-09-18T12:00:00Z", item(550, 1, "null", "1.0")));
        when(values.get(LAST_GOOD_KEY)).thenThrow(new QueryTimeoutException("redis is slow"));

        TrendingSnapshot snapshot =
                new RedisTrendingSnapshotReader(partiallyFailing, objectMapper)
                        .read()
                        .orElseThrow();

        assertThat(snapshot.stale()).isFalse();
        assertThat(snapshot.items().getFirst().movieId()).isEqualTo(550);
        verify(values, never()).get(LAST_GOOD_KEY);
    }

    @Test
    void reportsUnavailableWhenRedisReadFails() {
        StringRedisTemplate failing = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(failing.opsForValue()).thenReturn(values);
        when(values.get(CURRENT_KEY)).thenThrow(new QueryTimeoutException("redis is unavailable"));

        RedisTrendingSnapshotReader reader = new RedisTrendingSnapshotReader(failing, objectMapper);

        assertThatThrownBy(reader::read)
                .isInstanceOf(BusinessException.class)
                .extracting(failure -> ((BusinessException) failure).getErrorCode())
                .isEqualTo(ErrorCode.TREND_UNAVAILABLE);
    }

    /** 애플리케이션 재시작처럼 상태 없는 새 인스턴스로 읽는다. */
    private RedisTrendingSnapshotReader reader() {
        return new RedisTrendingSnapshotReader(redisTemplate, objectMapper);
    }

    private static String snapshot(String updatedAt, String items) {
        return "{\"schemaVersion\":1,"
                + "\"period\":{\"from\":\"2026-09-18T11:00:00Z\",\"to\":\"2026-09-18T12:00:00Z\"},"
                + "\"updatedAt\":\""
                + updatedAt
                + "\",\"items\":["
                + items
                + "]}";
    }

    private static String item(long movieId, int rank, String rankDelta, String trendScore) {
        return "{\"movieId\":"
                + movieId
                + ",\"rank\":"
                + rank
                + ",\"rankDelta\":"
                + rankDelta
                + ",\"trendScore\":"
                + trendScore
                + "}";
    }
}
