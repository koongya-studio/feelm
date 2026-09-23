package com.feelm.backend.domain.ott;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/** No OTT fixtures: actual Flyway must prepare the catalog before the HTTP requests. */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
class OttBootstrapApiContractTest extends SharedPostgresContractTest {

    private static final Path SEED =
            Path.of("src/main/resources/db/migration/V202609142240__seed_approved_ott_catalog.sql");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;
    @Autowired ObjectMapper json;
    @Autowired Flyway flyway;

    @BeforeEach
    void resetAndApplySeedMigration() throws Exception {
        jdbc.execute("TRUNCATE TABLE users, movies, otts RESTART IDENTITY CASCADE");
        jdbc.execute(Files.readString(SEED));
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void initializesCatalogAndSubscriptionsWithoutMovieIngestion() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movies", Integer.class)).isZero();
        var response =
                mvc.perform(get("/api/v1/otts"))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "max-age=86400, public"))
                        .andReturn()
                        .getResponse();
        var items = json.readTree(response.getContentAsString()).get("items");
        var approved = Files.readAllLines(Path.of("scripts/samples/ott-provider-mapping.csv"));
        assertThat(items.size()).isEqualTo(5);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            String[] fields = approved.get(i + 1).split(",", -1);
            var item = items.get(i);
            long id = item.get("ottId").asLong();
            ids.add(id);
            assertThat(item.size()).isEqualTo(4);
            assertThat(item.get("code").asText()).isEqualTo(fields[1]);
            assertThat(item.get("name").asText()).isEqualTo(fields[2]);
            assertThat(item.get("logoUrl").asText())
                    .isEqualTo("https://image.tmdb.org/t/p/original" + fields[3]);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT tmdb_provider_id FROM otts WHERE id=?",
                                    Integer.class,
                                    id))
                    .isEqualTo(Integer.valueOf(fields[0]));
        }
        assertThat(ids).isSorted().doesNotHaveDuplicates();
        assertThat(ids.getFirst()).isNotEqualTo(8L);
        jdbc.update(
                """
                INSERT INTO users(id,email,nickname,birth_date,gender,status)
                VALUES (381,'bootstrap@example.invalid','bootstrap','2000-01-01','OTHER','ACTIVE')
                """);
        String bearer = "Bearer " + tokens.createAccessToken(381);
        String body = json.writeValueAsString(java.util.Map.of("ottIds", ids));
        for (int repeat = 0; repeat < 2; repeat++) {
            mvc.perform(
                            put("/api/v1/users/me/otts")
                                    .header("Authorization", bearer)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ottIds.length()").value(5));
            var subscribed =
                    mvc.perform(get("/api/v1/users/me/otts").header("Authorization", bearer))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            assertThat(json.readTree(subscribed)).isEqualTo(json.readTree(body));
        }
        mvc.perform(get("/api/v1/users/me/otts")).andExpect(status().isUnauthorized());
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList("SELECT id FROM otts ORDER BY id", Long.class)).isEqualTo(ids);
    }
}
