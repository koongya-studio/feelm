package com.feelm.backend.domain.recommendation;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.recommendation.model.OttCandidateSnapshot;
import com.feelm.backend.domain.recommendation.model.OttCandidateSnapshot.ScoredMovie;
import com.feelm.backend.domain.recommendation.repository.OttCandidateReader;
import com.feelm.backend.domain.recommendation.repository.OttRecommendationRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
class OttRecommendationApiContractTest extends SharedPostgresContractTest {

    private static final long USER = 10;
    private static final String PATH = "/api/v1/recommendations/otts";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;
    @MockitoBean OttCandidateReader reader;
    @MockitoSpyBean OttRecommendationRepository repository;

    @BeforeEach
    void fixtures() {
        reset(reader, repository);
        jdbc.execute(
                "TRUNCATE TABLE user_movie_dismissals, ratings, watch_records,"
                        + " user_otts, movie_ott_providers, movies, otts, users"
                        + " RESTART IDENTITY CASCADE");
        user(USER);
        user(20);
        ott(1, "NETFLIX");
        ott(2, "DISNEY_PLUS");
        for (long id = 100; id <= 108; id++) {
            movie(id);
            provider(id, 1);
        }
        provider(100, 2);
        provider(107, 2);
        jdbc.update("INSERT INTO user_otts(user_id,ott_id) VALUES (?,?)", USER, 2);
    }

    @Test
    void filtersOwnWatchRatingAndDismissalButKeepsExpiredAndOtherUsersRecords() throws Exception {
        candidates(100, 101, 102, 103, 104, 105, 106, 107, 108);
        watch(USER, 101, "LINK_CLICKED");
        watch(USER, 102, "WATCHED_UNRATED");
        watch(USER, 103, "RATED");
        watch(USER, 104, "EXPIRED");
        watch(20, 105, "RATED");
        watch(USER, 108, "LINK_CLICKED");
        jdbc.update(
                "UPDATE watch_records SET expires_at = now() - INTERVAL '1 day'"
                        + " WHERE user_id = ? AND movie_id = ?",
                USER,
                108);
        jdbc.update("INSERT INTO ratings(user_id,movie_id,score) VALUES (?,?,?)", USER, 106, 5);
        jdbc.update("INSERT INTO user_movie_dismissals(user_id,movie_id) VALUES (?,?)", USER, 107);

        ownRequest()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insufficientData").value(false))
                .andExpect(jsonPath("$.items[0].ott.code").value("NETFLIX"))
                .andExpect(jsonPath("$.items[0].matchCount").value(4))
                .andExpect(jsonPath("$.items[0].topMovies[0].movieId").value(100))
                .andExpect(jsonPath("$.items[0].topMovies[1].movieId").value(104))
                .andExpect(jsonPath("$.items[0].topMovies[2].movieId").value(105))
                .andExpect(jsonPath("$.items[1].ott.code").value("DISNEY_PLUS"))
                .andExpect(jsonPath("$.items[1].matchCount").value(1))
                .andExpect(jsonPath("$.items[1].subscribed").value(true))
                .andExpect(jsonPath("$.items[1].topMovies[0].movieId").value(100));
    }

    @Test
    void returnsFallbackCandidatesWithoutRatingsOrOnboarding() throws Exception {
        candidates(100, 101);

        ownRequest()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insufficientData").value(false))
                .andExpect(jsonPath("$.items[0].matchCount").value(2));
    }

    @Test
    void returnsEmptyItemsWhenEveryCandidateIsFiltered() throws Exception {
        candidates(100);
        jdbc.update("INSERT INTO user_movie_dismissals(user_id,movie_id) VALUES (?,?)", USER, 100);

        ownRequest().andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void requiresAuthenticationAndReportsCandidateFailure() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        when(reader.read(USER))
                .thenThrow(new BusinessException(ErrorCode.RECOMMENDATION_UNAVAILABLE));
        ownRequest()
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RECOMMENDATION_UNAVAILABLE"));
    }

    @Test
    void reportsDatabaseFailureAsRecommendationUnavailable() throws Exception {
        candidates(100);
        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .when(repository)
                .findAvailableMovies(
                        org.mockito.ArgumentMatchers.eq(USER),
                        org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.any());

        ownRequest()
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RECOMMENDATION_UNAVAILABLE"));
    }

    @Test
    void readsOnlyAuthenticatedUsersCandidatesAndSubscriptions() throws Exception {
        candidates(100);
        when(reader.read(20))
                .thenReturn(
                        new OttCandidateSnapshot(List.of(new ScoredMovie(107, BigDecimal.ZERO))));

        ownRequest()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].matchCount").value(1))
                .andExpect(jsonPath("$.items[1].subscribed").value(true));
        mvc.perform(get(PATH).header("Authorization", "Bearer " + tokens.createAccessToken(20)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].topMovies[0].movieId").value(107))
                .andExpect(jsonPath("$.items[0].subscribed").value(false));
    }

    private org.springframework.test.web.servlet.ResultActions ownRequest() throws Exception {
        return mvc.perform(
                get(PATH).header("Authorization", "Bearer " + tokens.createAccessToken(USER)));
    }

    private void candidates(long... movieIds) {
        List<ScoredMovie> taste =
                java.util.Arrays.stream(movieIds)
                        .mapToObj(id -> new ScoredMovie(id, BigDecimal.ZERO))
                        .toList();
        when(reader.read(USER)).thenReturn(new OttCandidateSnapshot(taste));
    }

    private void user(long id) {
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                        + " VALUES (?,?,?,DATE '2000-01-01','OTHER','ACTIVE')",
                id,
                "user" + id + "@example.test",
                "user" + id);
    }

    private void ott(long id, String code) {
        jdbc.update(
                "INSERT INTO otts(id,code,name,tmdb_provider_id) VALUES (?,?,?,?)",
                id,
                code,
                code,
                Math.toIntExact(id + 1000));
    }

    private void movie(long id) {
        jdbc.update(
                "INSERT INTO movies(id,tmdb_id,title) VALUES (?,?,?)",
                id,
                Math.toIntExact(id + 10000),
                "movie-" + id);
    }

    private void provider(long movieId, long ottId) {
        jdbc.update(
                "INSERT INTO movie_ott_providers(movie_id,ott_id) VALUES (?,?)", movieId, ottId);
    }

    private void watch(long userId, long movieId, String watchStatus) {
        jdbc.update(
                "INSERT INTO watch_records(user_id,movie_id,status) VALUES (?,?,?)",
                userId,
                movieId,
                watchStatus);
    }
}
