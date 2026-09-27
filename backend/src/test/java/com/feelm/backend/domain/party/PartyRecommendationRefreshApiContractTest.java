package com.feelm.backend.domain.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.party.model.PartyCandidateSnapshot;
import com.feelm.backend.domain.party.model.PartyInputId;
import com.feelm.backend.domain.party.model.PartyInputSnapshot;
import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.infrastructure.redis.RedisPartyCandidateReader;
import com.feelm.backend.infrastructure.redis.RedisPartyInputReader;
import com.feelm.backend.support.SharedPostgresContractTest;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * API-058 다시 받기(S15P21E106-792). 노출 커서는 {@code exposed_at} 시각 동일성으로 구분한 묶음이며, 일반 조회는 묶음을 그대로 돌려주고
 * {@code refresh=true}만 미노출 유효 후보로 다음 묶음을 만든다.
 */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
class PartyRecommendationRefreshApiContractTest extends SharedPostgresContractTest {
    private static final Instant GENERATED_AT = Instant.parse("2026-09-17T05:21:00Z");
    private static final long FIRST_MOVIE = 101;
    private static final long LAST_MOVIE = 160;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;
    @MockitoBean RedisPartyInputReader inputReader;
    @MockitoBean RedisPartyCandidateReader candidateReader;

    private ExecutorService executor;

    @BeforeEach
    void fixtures() {
        reset(inputReader, candidateReader);
        jdbc.execute(
                "TRUNCATE TABLE user_movie_dismissals, ratings, watch_records,"
                        + " party_movie_exposures, recommendation_items, recommendation_sets,"
                        + " party_members, parties, movie_ott_providers, movies, otts, users"
                        + " RESTART IDENTITY CASCADE");
        user(10);
        user(20);
        ott(1, "NETFLIX");
        for (long movieId = FIRST_MOVIE; movieId <= LAST_MOVIE; movieId++) {
            movie(movieId);
            jdbc.update("INSERT INTO movie_ott_providers(movie_id,ott_id) VALUES (?,1)", movieId);
        }
    }

    @AfterEach
    void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("T1 다시 받기는 아직 노출하지 않은 후보부터 size개를 돌려주고 직전 응답과 겹치지 않는다")
    void refreshReturnsNextUnexposedBundleWithoutOverlap() throws Exception {
        long partyId = party();
        stub(partyId, movies(101, 112));

        assertThat(movieIds(plain(partyId, 3))).containsExactly(101, 102, 103);
        String refreshed = body(refresh(partyId, 3).andExpect(jsonPath("$.hasNext").value(true)));
        assertThat(movieIds(refreshed)).containsExactly(104, 105, 106);

        assertThat(count("recommendation_sets")).isEqualTo(1);
        assertThat(exposedCount()).isEqualTo(6);
        assertThat(distinctExposureTimes()).isEqualTo(2);
    }

    @Test
    @DisplayName("T2 다시 받기 없이 조회를 반복하면 직전에 노출한 묶음을 그대로 돌려준다")
    void plainFetchRepeatsCurrentBundleAfterRefresh() throws Exception {
        long partyId = party();
        stub(partyId, movies(101, 112));
        plain(partyId, 3);
        refresh(partyId, 3);
        List<OffsetDateTime> before = exposureTimes();

        assertThat(movieIds(plain(partyId, 3))).containsExactly(104, 105, 106);
        assertThat(movieIds(plain(partyId, 3))).containsExactly(104, 105, 106);
        assertThat(exposedCount()).isEqualTo(6);
        assertThat(exposureTimes()).isEqualTo(before);
    }

    @Test
    @DisplayName("T3 size를 늘린 일반 조회도 현재 묶음만 돌려주고 보충하지 않는다")
    void plainFetchReturnsOnlyCurrentBundleEvenWhenSizeGrows() throws Exception {
        long partyId = party();
        stub(partyId, movies(101, 112));
        plain(partyId, 3);

        assertThat(movieIds(plain(partyId, 5))).containsExactly(101, 102, 103);
        assertThat(exposedCount()).isEqualTo(3);
        assertThat(movieIds(refresh(partyId, 5))).containsExactly(104, 105, 106, 107, 108);
    }

    @Test
    @DisplayName("T4 노출 이력이 없는 세트의 다시 받기는 첫 조회와 같다")
    void refreshOnFreshSetBehavesAsFirstExposure() throws Exception {
        long partyId = party();
        stub(partyId, movies(101, 112));

        assertThat(movieIds(refresh(partyId, 3))).containsExactly(101, 102, 103);
        assertThat(count("recommendation_sets")).isEqualTo(1);
    }

    @Test
    @DisplayName("T5 파티원이 평가·관심없음·감상 확정한 영화는 다시 받기 결과에서 제외된다")
    void refreshSkipsMoviesAnyMemberRatedDismissedOrWatched() throws Exception {
        long partyId = party();
        stub(partyId, movies(101, 112));
        plain(partyId, 3);
        rate(10, 104);
        dismiss(20, 105);
        watch(10, 106);

        assertThat(movieIds(refresh(partyId, 3))).containsExactly(107, 108, 109);
        assertThat(exposedAt(104)).isNull();
        assertThat(exposedAt(105)).isNull();
        assertThat(exposedAt(106)).isNull();
    }

    @Test
    @DisplayName("T6 파티원이 노출 중인 영화를 평가해도 일반 조회의 묶음은 바뀌지 않는다")
    void plainFetchKeepsBundleUnchangedAfterMemberRatesShownMovie() throws Exception {
        long partyId = party();
        stub(partyId, movies(101, 112));
        plain(partyId, 3);
        List<OffsetDateTime> before = exposureTimes();
        rate(20, 102);

        assertThat(movieIds(plain(partyId, 3))).containsExactly(101, 102, 103);
        assertThat(exposedCount()).isEqualTo(3);
        assertThat(exposureTimes()).isEqualTo(before);
    }

    @Test
    @DisplayName("T7 노출 중인 영화를 모두 평가한 뒤의 다시 받기는 다음 묶음으로 넘어간다")
    void refreshExcludesShownMoviesThatMembersRatedAndMovesOn() throws Exception {
        long partyId = party();
        stub(partyId, movies(101, 112));
        plain(partyId, 3);
        rate(10, 101);
        rate(20, 102);
        rate(10, 103);

        assertThat(movieIds(refresh(partyId, 3))).containsExactly(104, 105, 106);
        assertThat(count("recommendation_sets")).isEqualTo(1);
    }

    @Test
    @DisplayName("T8 저장한 후보를 모두 소진한 다시 받기는 마지막 묶음을 그대로 돌려주고 hasNext=false다")
    void exhaustedRefreshRepeatsLastBundleWithHasNextFalse() throws Exception {
        long partyId = party();
        stub(partyId, movies(101, 104));

        String first = body(plain(partyId, 3).andExpect(jsonPath("$.hasNext").value(true)));
        assertThat(movieIds(first)).containsExactly(101, 102, 103);
        long setId = setId(first);

        String last = body(refresh(partyId, 3).andExpect(jsonPath("$.hasNext").value(false)));
        assertThat(movieIds(last)).containsExactly(104);

        String exhausted = body(refresh(partyId, 3).andExpect(jsonPath("$.hasNext").value(false)));
        assertThat(movieIds(exhausted)).containsExactly(104);
        assertThat(setId(exhausted)).isEqualTo(setId);

        assertThat(movieIds(plain(partyId, 3).andExpect(jsonPath("$.hasNext").value(false))))
                .containsExactly(104);
        assertThat(exposedCount()).isEqualTo(4);
        assertThat(count("recommendation_sets")).isEqualTo(1);
    }

    @Test
    @DisplayName("T9 노출 중인 영화를 모두 평가했어도 일반 조회는 그 묶음을 그대로 돌려준다")
    void plainFetchOnFullyRatedBundleStillReturnsIt() throws Exception {
        long partyId = party();
        stub(partyId, movies(101, 103));
        plain(partyId, 3);
        rate(10, 101);
        rate(20, 102);
        rate(10, 103);

        String response =
                body(
                        plain(partyId, 3)
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.hasNext").value(false)));
        assertThat(movieIds(response)).containsExactly(101, 102, 103);
        assertThat(count("recommendation_sets")).isEqualTo(1);
    }

    @Test
    @DisplayName("T9-1 남은 후보를 파티원이 평가하면 묶음은 그대로인 채 hasNext만 false가 된다")
    void hasNextTurnsFalseWhenRemainingCandidatesBecomeIneligible() throws Exception {
        long partyId = party();
        stub(partyId, movies(101, 104));
        plain(partyId, 3).andExpect(jsonPath("$.hasNext").value(true));
        rate(10, 104);

        assertThat(movieIds(plain(partyId, 3).andExpect(jsonPath("$.hasNext").value(false))))
                .containsExactly(101, 102, 103);
        assertThat(exposedCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("T10 기존 10개짜리 세트는 가장 최근 노출 시각의 항목만 묶음이 되고 오류 없이 다시 받기가 이어진다")
    void legacyTenItemSetWithMixedExposureTimesServesLatestGroup() throws Exception {
        long partyId = party();
        PartyInputSnapshot input = input();
        String inputId = PartyInputId.from(partyId, input);
        long setId = legacySet(partyId, inputId);
        OffsetDateTime t1 = OffsetDateTime.parse("2026-09-18T00:00:00Z");
        OffsetDateTime t2 = t1.plusMinutes(5);
        for (long movieId = 101; movieId <= 110; movieId++) {
            OffsetDateTime exposedAt = movieId <= 103 ? t1 : movieId == 104 ? t2 : null;
            legacyItem(setId, movieId, (int) (movieId - 100), exposedAt);
        }
        when(inputReader.read(List.of(10L, 20L))).thenReturn(input);
        when(candidateReader.read(partyId))
                .thenReturn(snapshot(partyId, inputId, "v1", movies(101, 110)));

        assertThat(movieIds(plain(partyId, 3))).containsExactly(104);
        assertThat(movieIds(refresh(partyId, 3))).containsExactly(105, 106, 107);
        assertThat(movieIds(refresh(partyId, 3).andExpect(jsonPath("$.hasNext").value(false))))
                .containsExactly(108, 109, 110);
        assertThat(movieIds(refresh(partyId, 3).andExpect(jsonPath("$.hasNext").value(false))))
                .containsExactly(108, 109, 110);
        assertThat(count("recommendation_sets")).isEqualTo(1);
    }

    @Test
    void rollsBackExposureWhenSnapshotCannotBeStored() throws Exception {
        long partyId = party();
        stub(partyId, movies(101, 110));
        jdbc.execute(
                "ALTER TABLE party_popcorn_snapshots ADD CONSTRAINT snapshot_failure_test CHECK (party_id < 0)");
        try {
            request(10, partyId, 3, null).andExpect(status().is5xxServerError());
            assertThat(count("recommendation_sets")).isZero();
            assertThat(count("recommendation_items")).isZero();
            assertThat(count("party_movie_exposures")).isZero();
            assertThat(count("party_popcorn_snapshots")).isZero();
        } finally {
            jdbc.execute(
                    "ALTER TABLE party_popcorn_snapshots DROP CONSTRAINT snapshot_failure_test");
        }
    }

    @Test
    @DisplayName("T11 새 세트는 유효 후보를 순위대로 최대 50편 저장한다")
    void newSetStoresUpToFiftyEligibleCandidates() throws Exception {
        long partyId = party();
        stub(partyId, movies(101, 160));
        rate(10, 105);

        plain(partyId, 3).andExpect(status().isOk());

        assertThat(count("recommendation_items")).isEqualTo(50);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT max(position) FROM recommendation_items", Integer.class))
                .isEqualTo(50);
        List<Long> stored =
                jdbc.queryForList(
                        "SELECT movie_id FROM recommendation_items ORDER BY position", Long.class);
        // 105를 건너뛴 50편은 101~151이다.
        assertThat(stored).doesNotContain(105L).contains(101L, 151L).doesNotContain(152L);
    }

    @Test
    @DisplayName("T12 같은 파티의 동시 다시 받기에도 노출 이력과 커서가 어긋나지 않는다")
    void concurrentRefreshesKeepExposureAndCursorConsistent() throws Exception {
        long partyId = party();
        stub(partyId, movies(101, 130));
        plain(partyId, 3);

        int threads = 5;
        executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> concurrentRefresh(partyId, ready, start)));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        Set<Integer> seen = new HashSet<>();
        for (Future<String> future : futures) {
            List<Integer> ids = movieIds(future.get(30, TimeUnit.SECONDS));
            assertThat(ids).hasSize(3);
            assertThat(seen).doesNotContainAnyElementsOf(ids);
            seen.addAll(ids);
        }
        assertThat(exposedCount()).isEqualTo(3 + 3 * threads);
        assertThat(distinctExposureTimes()).isEqualTo(1 + threads);
        assertThat(count("party_popcorn_snapshots")).isEqualTo(1 + threads);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM recommendation_items i WHERE i.exposed_at IS NOT NULL AND NOT EXISTS (SELECT 1 FROM party_popcorn_snapshots s WHERE s.set_id=i.set_id AND s.exposed_at=i.exposed_at)",
                                Long.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM recommendation_items"
                                        + " WHERE exposed_at IS NOT NULL"
                                        + " GROUP BY exposed_at ORDER BY exposed_at DESC LIMIT 1",
                                Integer.class))
                .isEqualTo(3);

        List<Long> latestBundle =
                jdbc.queryForList(
                        "SELECT movie_id FROM recommendation_items WHERE exposed_at ="
                                + " (SELECT max(exposed_at) FROM recommendation_items)"
                                + " ORDER BY position",
                        Long.class);
        assertThat(movieIds(plain(partyId, 3)))
                .containsExactlyElementsOf(latestBundle.stream().map(Long::intValue).toList());
    }

    /** 노출과 같은 트랜잭션에서 파티 추천 이력 표가 갱신된다(S15P21E106-815). 다시 받기의 다음 묶음도 같은 파티 행에 누적된다. */
    @Test
    @DisplayName("815-T13 노출할 때마다 party_movie_exposures가 같은 트랜잭션에서 갱신된다")
    void recordsPartyExposureHistoryWithEveryExposure() throws Exception {
        long partyId = party();
        stub(partyId, movies(FIRST_MOVIE, FIRST_MOVIE + 9));

        String first = body(plain(partyId, 3).andExpect(status().isOk()));
        String second = body(refresh(partyId, 3).andExpect(status().isOk()));

        List<Long> recorded =
                jdbc.queryForList(
                        "SELECT movie_id FROM party_movie_exposures WHERE party_id=? ORDER BY movie_id",
                        Long.class,
                        partyId);
        List<Long> shown = new java.util.ArrayList<>();
        movieIds(first).forEach(id -> shown.add(id.longValue()));
        movieIds(second).forEach(id -> shown.add(id.longValue()));
        assertThat(recorded).containsExactlyInAnyOrderElementsOf(shown);
        // 두 묶음의 노출 시각이 그대로 이력에 실려야 한다(같은 트랜잭션, 항목 행에서 읽음).
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(DISTINCT exposed_at) FROM party_movie_exposures"
                                        + " WHERE party_id=?",
                                Long.class,
                                partyId))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM party_movie_exposures e"
                                        + " JOIN recommendation_items i ON i.id=e.recommendation_item_id"
                                        + " WHERE e.party_id=? AND i.exposed_at IS NULL",
                                Long.class,
                                partyId))
                .isZero();
    }

    /**
     * 가드({@code set_id < EXCLUDED.set_id})의 거절 경로(S15P21E106-815 리뷰 B-2). 파티원이 나갔다 돌아오면 입력이 이전 값으로
     * 돌아가 예전 세트가 다시 서빙된다. 그 세트가 노출한 영화 중 더 새 세트에 이미 기록된 영화는 이력 위치가 바뀌지 않고, 나머지는 예전 세트로 기록된다. 응답은
     * 영향받지 않는다.
     */
    @Test
    @DisplayName("815-T15 예전 세트가 다시 서빙돼도 이력은 더 새 세트의 노출을 유지한다")
    void keepsNewerExposureWhenOlderSetIsReServed() throws Exception {
        long partyId = party();
        PartyInputSnapshot input = input();
        String revertedInputId = PartyInputId.from(partyId, input);
        long olderSet = legacySet(partyId, revertedInputId);
        for (long movieId = 101; movieId <= 105; movieId++) {
            legacyItem(olderSet, movieId, (int) (movieId - 100), null);
        }
        long newerSet = legacySet(partyId, "input-while-member-c-joined");
        OffsetDateTime newerExposedAt = OffsetDateTime.parse("2026-09-18T00:00:00Z");
        legacyItem(newerSet, 101, 1, newerExposedAt);
        long newerItem =
                jdbc.queryForObject(
                        "SELECT id FROM recommendation_items WHERE set_id=? AND movie_id=101",
                        Long.class,
                        newerSet);
        jdbc.update(
                "INSERT INTO party_movie_exposures"
                        + " (party_id,movie_id,recommendation_item_id,set_id,exposed_at) VALUES (?,?,?,?,?)",
                partyId,
                101,
                newerItem,
                newerSet,
                newerExposedAt);
        when(inputReader.read(List.of(10L, 20L))).thenReturn(input);
        when(candidateReader.read(partyId))
                .thenReturn(snapshot(partyId, revertedInputId, "v1", movies(101, 105)));

        assertThat(movieIds(plain(partyId, 3).andExpect(status().isOk())))
                .containsExactly(101, 102, 103);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT set_id FROM party_movie_exposures WHERE party_id=? AND movie_id=101",
                                Long.class,
                                partyId))
                .isEqualTo(newerSet);
        assertThat(
                        jdbc.queryForList(
                                "SELECT set_id FROM party_movie_exposures"
                                        + " WHERE party_id=? AND movie_id IN (102,103) ORDER BY movie_id",
                                Long.class,
                                partyId))
                .containsExactly(olderSet, olderSet);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM recommendation_items"
                                        + " WHERE set_id=? AND exposed_at IS NOT NULL",
                                Long.class,
                                olderSet))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("T13 refresh 값이 boolean이 아니면 400 VALIDATION_FAILED다")
    void rejectsInvalidRefreshValue() throws Exception {
        long partyId = party();
        stub(partyId, movies(101, 112));

        mvc.perform(
                        get("/api/v1/parties/" + partyId + "/recommendations")
                                .param("refresh", "maybe")
                                .header("Authorization", bearer(10)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private String concurrentRefresh(long partyId, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return body(refresh(partyId, 3).andExpect(status().isOk()));
    }

    private ResultActions plain(long partyId, int size) throws Exception {
        return request(10, partyId, size, null).andExpect(status().isOk());
    }

    private ResultActions refresh(long partyId, int size) throws Exception {
        return request(10, partyId, size, true).andExpect(status().isOk());
    }

    private ResultActions request(long userId, long partyId, int size, Boolean refresh)
            throws Exception {
        var request =
                get("/api/v1/parties/" + partyId + "/recommendations")
                        .param("size", Integer.toString(size))
                        .header("Authorization", bearer(userId));
        if (refresh != null) {
            request.param("refresh", refresh.toString());
        }
        return mvc.perform(request);
    }

    private String bearer(long userId) {
        return "Bearer " + tokens.createAccessToken(userId);
    }

    private static String body(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString();
    }

    private static List<Integer> movieIds(ResultActions actions) throws Exception {
        return movieIds(body(actions));
    }

    private static List<Integer> movieIds(String body) {
        return JsonPath.read(body, "$.items[*].movie.movieId");
    }

    private static long setId(String body) {
        return ((Number) JsonPath.read(body, "$.recommendationSetId")).longValue();
    }

    private static List<Long> movies(long from, long to) {
        return LongStream.rangeClosed(from, to).boxed().toList();
    }

    private void stub(long partyId, List<Long> movieIds) {
        PartyInputSnapshot input = input();
        when(inputReader.read(List.of(10L, 20L))).thenReturn(input);
        when(candidateReader.read(partyId))
                .thenReturn(snapshot(partyId, PartyInputId.from(partyId, input), "v1", movieIds));
    }

    private PartyInputSnapshot input() {
        return new PartyInputSnapshot(
                "v1",
                List.of(
                        new PartyInputSnapshot.Member(10, "g1", List.of(), List.of(101L)),
                        new PartyInputSnapshot.Member(20, "g2", List.of(), List.of(101L))));
    }

    private PartyCandidateSnapshot snapshot(
            long partyId, String inputId, String modelVersion, List<Long> movieIds) {
        List<PartyCandidateSnapshot.Candidate> candidates =
                movieIds.stream().map(PartyCandidateSnapshot.Candidate::new).toList();
        return new PartyCandidateSnapshot(
                partyId, modelVersion, "party-mean-fit-v2", inputId, GENERATED_AT, candidates);
    }

    private long party() {
        Long id =
                jdbc.queryForObject(
                        "INSERT INTO parties(name,owner_id,status,created_at)"
                                + " VALUES ('party',10,'ACTIVE',?) RETURNING id",
                        Long.class,
                        Timestamp.from(GENERATED_AT));
        jdbc.update(
                "INSERT INTO party_members(party_id,user_id,role,joined_at)"
                        + " VALUES (?,10,'OWNER',?),(?,20,'MEMBER',?)",
                id,
                Timestamp.from(GENERATED_AT),
                id,
                Timestamp.from(GENERATED_AT.plusSeconds(1)));
        return id;
    }

    /** S15P21E106-792 이전에 만들어진 세트: 후보 10편, 노출 시각이 요청마다 다르다. */
    private long legacySet(long partyId, String inputId) {
        return jdbc.queryForObject(
                "INSERT INTO recommendation_sets"
                        + " (party_id,input_version,model_version,candidate_version,generated_at)"
                        + " VALUES (?,?,'v1',0,?) RETURNING id",
                Long.class,
                partyId,
                inputId,
                Timestamp.from(GENERATED_AT));
    }

    private void legacyItem(long setId, long movieId, int position, OffsetDateTime exposedAt) {
        jdbc.update(
                "INSERT INTO recommendation_items(set_id,movie_id,type,position,reason,exposed_at)"
                        + " VALUES (?,?,'PARTY',?,CAST('{}' AS jsonb),?)",
                setId,
                movieId,
                position,
                exposedAt);
    }

    private void rate(long userId, long movieId) {
        jdbc.update("INSERT INTO ratings(user_id,movie_id,score) VALUES (?,?,5)", userId, movieId);
    }

    private void dismiss(long userId, long movieId) {
        jdbc.update(
                "INSERT INTO user_movie_dismissals(user_id,movie_id) VALUES (?,?)",
                userId,
                movieId);
    }

    private void watch(long userId, long movieId) {
        jdbc.update(
                "INSERT INTO watch_records(user_id,movie_id,status)"
                        + " VALUES (?,?,'WATCHED_UNRATED')",
                userId,
                movieId);
    }

    private void user(long id) {
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                        + " VALUES (?,?,?,DATE '2000-01-01','OTHER','ACTIVE')",
                id,
                "user" + id + "@example.test",
                "user" + id);
    }

    private void movie(long id) {
        jdbc.update(
                "INSERT INTO movies(id,tmdb_id,title) VALUES (?,?,?)", id, id + 1000, "movie" + id);
    }

    private void ott(long id, String code) {
        jdbc.update(
                "INSERT INTO otts(id,code,name,tmdb_provider_id) VALUES (?,?,?,?)",
                id,
                code,
                code,
                id + 1000);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private long exposedCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM recommendation_items WHERE exposed_at IS NOT NULL",
                Long.class);
    }

    private long distinctExposureTimes() {
        return jdbc.queryForObject(
                "SELECT count(DISTINCT exposed_at) FROM recommendation_items"
                        + " WHERE exposed_at IS NOT NULL",
                Long.class);
    }

    private List<OffsetDateTime> exposureTimes() {
        return jdbc.queryForList(
                "SELECT exposed_at FROM recommendation_items ORDER BY position",
                OffsetDateTime.class);
    }

    private OffsetDateTime exposedAt(long movieId) {
        return jdbc.queryForObject(
                "SELECT exposed_at FROM recommendation_items WHERE movie_id=?",
                OffsetDateTime.class,
                movieId);
    }
}
