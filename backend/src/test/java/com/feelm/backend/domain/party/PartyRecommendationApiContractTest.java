package com.feelm.backend.domain.party;

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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
class PartyRecommendationApiContractTest extends SharedPostgresContractTest {
    private static final Instant GENERATED_AT = Instant.parse("2026-09-17T05:21:00Z");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;
    @MockitoBean RedisPartyInputReader inputReader;
    @MockitoBean RedisPartyCandidateReader candidateReader;

    @BeforeEach
    void fixtures() {
        reset(inputReader, candidateReader);
        jdbc.execute(
                "TRUNCATE TABLE user_movie_dismissals, ratings, watch_records,"
                        + " recommendation_items, recommendation_sets, party_members, parties,"
                        + " movie_ott_providers, movies, otts, users, popcorn_flavors RESTART IDENTITY CASCADE");
        user(10);
        user(20);
        user(30);
        ott(1, "NETFLIX");
        for (long movieId = 101; movieId <= 112; movieId++) {
            movie(movieId);
            jdbc.update("INSERT INTO movie_ott_providers(movie_id,ott_id) VALUES (?,1)", movieId);
        }
    }

    @Test
    void appliesSnapshotConstraintsWithoutChangingExistingRecords() throws Exception {
        long partyId = party("ACTIVE");
        stub(partyId, List.of(101L, 102L, 103L));
        request(10, partyId, 3).andExpect(status().isOk());
        String duplicate =
                "INSERT INTO party_popcorn_snapshots(party_id,set_id,exposed_at,members,recommendation_mode) SELECT party_id,set_id,exposed_at,members,recommendation_mode FROM party_popcorn_snapshots";
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(duplicate))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO party_popcorn_snapshots(party_id,set_id,exposed_at,members,recommendation_mode) VALUES (99999,99999,now(),'[]','PERSONALIZED')"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE party_popcorn_snapshots SET recommendation_mode='UNKNOWN'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        org.assertj.core.api.Assertions.assertThat(count("party_popcorn_snapshots")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(count("recommendation_items")).isEqualTo(3);
    }

    @Test
    void recordsAllEightFlavorsAndKeepsCountsAfterTasteChanges() throws Exception {
        long partyId = party("ACTIVE");
        for (int i = 0; i < 8; i++) {
            String code = "FLAVOR_" + (char) ('A' + i);
            jdbc.update(
                    "INSERT INTO popcorn_flavors(code,display_name,color,cluster_id,sort_order,representative_keywords,description) VALUES (?,?,'#abcdef',?,?,'[]'::jsonb,'test') ON CONFLICT(code) DO NOTHING",
                    code,
                    code,
                    i,
                    i + 1);
        }
        jdbc.update(
                "UPDATE movies SET representative_flavor_id=(SELECT id FROM popcorn_flavors WHERE code='FLAVOR_A') WHERE id=111");
        jdbc.update(
                "UPDATE movies SET representative_flavor_id=(SELECT id FROM popcorn_flavors WHERE code='FLAVOR_B') WHERE id=112");
        jdbc.update(
                "INSERT INTO ratings(user_id,movie_id,score) VALUES (10,111,5),(10,112,4),(20,112,3)");
        stub(partyId, List.of(101L, 102L, 103L));
        request(10, partyId, 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.popcorn.totalCount").value(3))
                .andExpect(jsonPath("$.popcorn.flavors.length()").value(8))
                .andExpect(jsonPath("$.popcorn.flavors[0].popcornCount").value(1))
                .andExpect(jsonPath("$.popcorn.flavors[1].popcornCount").value(2))
                .andExpect(jsonPath("$.popcorn.flavors[1].averageScore").value(3.5));
        jdbc.update("DELETE FROM ratings WHERE user_id IN (10,20)");
        request(20, partyId, 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.popcorn.totalCount").value(3));
    }

    @Test
    void recordsEmptyMixOnceAndPreservesItAfterTasteChange() throws Exception {
        long partyId = party("ACTIVE");
        stub(partyId, List.of(101L, 102L, 103L, 104L));
        request(10, partyId, 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.popcorn.totalCount").value(0))
                .andExpect(jsonPath("$.popcorn.members.length()").value(2));
        org.assertj.core.api.Assertions.assertThat(count("party_popcorn_snapshots")).isEqualTo(1);
        jdbc.update("INSERT INTO ratings(user_id,movie_id,score) VALUES (10,104,5)");
        request(20, partyId, 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.popcorn.totalCount").value(0));
        org.assertj.core.api.Assertions.assertThat(count("party_popcorn_snapshots")).isEqualTo(1);
        mvc.perform(
                        get("/api/v1/parties/" + partyId + "/popcorn")
                                .header("Authorization", "Bearer " + tokens.createAccessToken(10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestMix.totalCount").value(0));
        mvc.perform(
                        get("/api/v1/parties/" + partyId + "/recommendations/history")
                                .header("Authorization", "Bearer " + tokens.createAccessToken(10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].popcornSnapshots.length()").value(1));
    }

    @Test
    void preservesPopularityModeAcrossRedisChangesAndHistory() throws Exception {
        long partyId = party("ACTIVE");
        PartyInputSnapshot input = input();
        when(inputReader.read(List.of(10L, 20L))).thenReturn(input);
        var original =
                snapshot(
                        partyId,
                        PartyInputId.from(partyId, input),
                        "v1",
                        List.of(101L, 102L, 103L));
        when(candidateReader.read(partyId))
                .thenReturn(
                        new PartyCandidateSnapshot(
                                partyId,
                                original.modelVersion(),
                                original.algorithmVersion(),
                                original.inputId(),
                                original.generatedAt(),
                                original.candidates(),
                                "POPULARITY"));
        request(10, partyId, 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationMode").value("POPULARITY"));
        when(candidateReader.read(partyId)).thenReturn(original);
        request(20, partyId, 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationMode").value("POPULARITY"));
        mvc.perform(
                        get("/api/v1/parties/" + partyId + "/recommendations/history")
                                .header("Authorization", "Bearer " + tokens.createAccessToken(10)))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.items[0].popcornSnapshots[0].recommendationMode")
                                .value("POPULARITY"));
        long oldSet =
                jdbc.queryForObject(
                        "SELECT id FROM recommendation_sets WHERE party_id=?", Long.class, partyId);
        var changed =
                new PartyInputSnapshot(
                        "v1",
                        List.of(
                                new PartyInputSnapshot.Member(10, "g3", List.of(), List.of(101L)),
                                new PartyInputSnapshot.Member(20, "g2", List.of(), List.of(101L))));
        when(inputReader.read(List.of(10L, 20L))).thenReturn(changed);
        when(candidateReader.read(partyId))
                .thenReturn(
                        snapshot(
                                partyId,
                                PartyInputId.from(partyId, changed),
                                "v1",
                                List.of(104L, 105L, 106L)));
        request(10, partyId, 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationMode").value("PERSONALIZED"));
        org.assertj.core.api.Assertions.assertThat(
                        jdbc.queryForList(
                                "SELECT recommendation_mode FROM party_popcorn_snapshots ORDER BY id",
                                String.class))
                .containsExactly("POPULARITY", "PERSONALIZED");
        when(inputReader.read(List.of(10L, 20L))).thenReturn(input);
        when(candidateReader.read(partyId))
                .thenReturn(
                        snapshot(
                                partyId,
                                PartyInputId.from(partyId, input),
                                "v1",
                                List.of(101L, 102L, 103L, 107L, 108L, 109L)));
        // Previously stored set retains its mode; extend its unexposed pool to exercise older-set
        // reuse.
        jdbc.update(
                "INSERT INTO recommendation_items(set_id,movie_id,type,position,reason) VALUES (?,107,'PARTY',4,'{}')",
                oldSet);
        mvc.perform(
                        get("/api/v1/parties/" + partyId + "/recommendations")
                                .param("refresh", "true")
                                .header("Authorization", "Bearer " + tokens.createAccessToken(10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationMode").value("POPULARITY"));
        mvc.perform(
                        get("/api/v1/parties/" + partyId + "/popcorn")
                                .header("Authorization", "Bearer " + tokens.createAccessToken(10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestMix.recommendationSetId").value(oldSet));
        org.assertj.core.api.Assertions.assertThat(
                        jdbc.queryForList(
                                "SELECT recommendation_mode FROM party_popcorn_snapshots ORDER BY id",
                                String.class))
                .containsExactly("POPULARITY", "PERSONALIZED", "POPULARITY");
    }

    @Test
    void recordsNewExposureAndKeepsHistoricalUsersAnonymous() throws Exception {
        long partyId = party("ACTIVE");
        stub(partyId, List.of(101L, 102L, 103L, 104L, 105L, 106L));
        request(10, partyId, 3).andExpect(status().isOk());
        mvc.perform(
                        get("/api/v1/parties/" + partyId + "/recommendations")
                                .param("refresh", "true")
                                .header("Authorization", "Bearer " + tokens.createAccessToken(10)))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(count("party_popcorn_snapshots")).isEqualTo(2);
        jdbc.update("DELETE FROM party_members WHERE party_id=? AND user_id=20", partyId);
        jdbc.update(
                "UPDATE users SET status='WITHDRAWN',nickname='withdrawn-20',birth_date=NULL,gender=NULL WHERE id=20");
        mvc.perform(
                        get("/api/v1/parties/" + partyId + "/popcorn")
                                .header("Authorization", "Bearer " + tokens.createAccessToken(10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestMix.members[1].user.nickname").value("탈퇴한 사용자"))
                .andExpect(jsonPath("$.latestMix.members[1].user.avatarUrl").isEmpty());
    }

    @Test
    void memberGetsCurrentPartyRecommendationsAndReusesSet() throws Exception {
        long partyId = party("ACTIVE");
        stub(partyId, List.of(101L, 102L, 103L, 104L));

        request(20, partyId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partyId").value(partyId))
                .andExpect(jsonPath("$.generatedAt").value("2026-09-17T05:21:00Z"))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].type").value("PARTY"))
                .andExpect(jsonPath("$.items[0].movie.movieId").value(101))
                .andExpect(jsonPath("$.items[0].movie.ottProviders[0]").value("NETFLIX"))
                .andExpect(jsonPath("$.items[0].reason").doesNotExist())
                .andExpect(jsonPath("$.hasNext").value(true));

        // 일반 조회는 노출한 묶음을 그대로 돌려준다. size를 늘려도 보충하지 않는다(S15P21E106-792).
        request(10, partyId, 4)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.hasNext").value(true));
        org.assertj.core.api.Assertions.assertThat(count("recommendation_sets")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(count("recommendation_items")).isEqualTo(4);
    }

    @Test
    void excludesAnyMembersRatedWatchedAndDismissedMovies() throws Exception {
        long partyId = party("ACTIVE");
        stub(partyId, List.of(101L, 102L, 103L, 104L));
        jdbc.update("INSERT INTO ratings(user_id,movie_id,score) VALUES (10,101,5)");
        jdbc.update(
                "INSERT INTO watch_records(user_id,movie_id,status)"
                        + " VALUES (20,102,'WATCHED_UNRATED')");
        jdbc.update("INSERT INTO user_movie_dismissals(user_id,movie_id) VALUES (10,103)");

        request(20, partyId, 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].movie.movieId").value(104));
    }

    @Test
    void rejectsStaleResultsAndInvalidSize() throws Exception {
        long partyId = party("ACTIVE");
        PartyInputSnapshot input = input();
        when(inputReader.read(List.of(10L, 20L))).thenReturn(input);
        when(candidateReader.read(partyId))
                .thenReturn(snapshot(partyId, "other-input", "v1", List.of(101L)))
                .thenReturn(
                        snapshot(
                                partyId,
                                PartyInputId.from(partyId, input),
                                "old-model",
                                List.of(101L)));

        request(10, partyId, 3)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RECOMMENDATION_UNAVAILABLE"));
        request(10, partyId, 3)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RECOMMENDATION_UNAVAILABLE"));
        request(10, partyId, 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void enforcesMembershipAndClosedState() throws Exception {
        long active = party("ACTIVE");
        request(30, active, 3)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        long closed = party("CLOSED");
        request(10, closed, 3)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARTY_CLOSED"));
    }

    private org.springframework.test.web.servlet.ResultActions request(
            long userId, long partyId, Integer size) throws Exception {
        var request =
                get("/api/v1/parties/" + partyId + "/recommendations")
                        .header("Authorization", "Bearer " + tokens.createAccessToken(userId));
        if (size != null) {
            request.param("size", size.toString());
        }
        return mvc.perform(request);
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

    private long party(String status) {
        Long id =
                jdbc.queryForObject(
                        "INSERT INTO parties(name,owner_id,status,created_at,closed_reason,closed_at)"
                                + " VALUES ('party',10,?,?,?,?) RETURNING id",
                        Long.class,
                        status,
                        Timestamp.from(GENERATED_AT),
                        status.equals("CLOSED") ? "OWNER_CLOSED" : null,
                        status.equals("CLOSED") ? Timestamp.from(GENERATED_AT) : null);
        jdbc.update(
                "INSERT INTO party_members(party_id,user_id,role,joined_at)"
                        + " VALUES (?,10,'OWNER',?),(?,20,'MEMBER',?)",
                id,
                Timestamp.from(GENERATED_AT),
                id,
                Timestamp.from(GENERATED_AT.plusSeconds(1)));
        return id;
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
}
