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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.task.scheduling.enabled=false"
        })
@AutoConfigureMockMvc
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
                        + " movie_ott_providers, movies, otts, users RESTART IDENTITY CASCADE");
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
