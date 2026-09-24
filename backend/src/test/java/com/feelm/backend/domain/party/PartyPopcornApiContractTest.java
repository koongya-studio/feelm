package com.feelm.backend.domain.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** API-057: 실제 마이그레이션과 PostgreSQL을 사용하는 파티원 팝콘 HTTP 계약 테스트. */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
class PartyPopcornApiContractTest extends SharedPostgresContractTest {
    private static final Instant CREATED_AT = Instant.parse("2026-09-01T00:00:00Z");
    private static final String AVATAR_KEY = "33333333-3333-3333-3333-333333333333.jpg";
    private static final String AVATAR_URL = "/media/avatars/" + AVATAR_KEY;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;
    @Autowired ObjectMapper json;

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE TABLE users, popcorn_flavors RESTART IDENTITY CASCADE");
        user(10, "owner", "PRIVATE");
        user(20, "member", "PRIVATE");
        user(30, "empty", "PUBLIC");
        user(40, "outsider", "PUBLIC");
        for (int order = 1; order <= 8; order++) {
            flavor(order, "FLAVOR_" + order, order);
        }
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get(path(1))).andExpect(status().isUnauthorized());
        mvc.perform(get(path(1)).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void hidesMissingInaccessibleFormerMemberAndClosedParties() throws Exception {
        mvc.perform(get(path(999)).header("Authorization", bearer(10)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        long active = party(10, "ACTIVE");
        member(active, 20, "MEMBER", CREATED_AT.plusSeconds(1));
        mvc.perform(get(path(active)).header("Authorization", bearer(40)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        member(active, 40, "MEMBER", CREATED_AT.plusSeconds(2));
        jdbc.update("DELETE FROM party_members WHERE party_id=? AND user_id=40", active);
        mvc.perform(get(path(active)).header("Authorization", bearer(40)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        long closed = party(10, "CLOSED");
        mvc.perform(get(path(closed)).header("Authorization", bearer(10)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void aggregatesEveryCurrentMemberIncludingPrivateProfiles() throws Exception {
        jdbc.update("UPDATE users SET avatar_storage_key=? WHERE id=10", AVATAR_KEY);
        long party = party(10, "ACTIVE");
        member(party, 20, "MEMBER", CREATED_AT.plusSeconds(1));
        member(party, 30, "MEMBER", CREATED_AT.plusSeconds(2));

        movie(101, 1L);
        movie(102, 1L);
        movie(103, 2L);
        movie(104, null);
        rating(10, 101, "4.5");
        rating(10, 102, "3.5");
        rating(20, 103, "5.0");
        rating(20, 104, "1.0");
        rating(40, 101, "1.0");

        JsonNode body =
                json.readTree(
                        mvc.perform(get(path(party)).header("Authorization", bearer(20)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.partyId").value(party))
                                .andExpect(jsonPath("$.members.length()").value(3))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());

        JsonNode owner = memberBody(body, 10);
        assertThat(owner.path("user").path("nickname").asText()).isEqualTo("owner");
        assertThat(owner.path("user").path("avatarUrl").asText()).isEqualTo(AVATAR_URL);
        assertThat(owner.path("totalCount").asLong()).isEqualTo(2);
        assertThat(owner.path("flavors")).hasSize(8);
        assertFlavor(owner, "FLAVOR_1", 2, "4.0");
        assertEmptyFlavor(owner, "FLAVOR_8");

        JsonNode member = memberBody(body, 20);
        assertThat(member.path("user").path("nickname").asText()).isEqualTo("member");
        assertThat(member.path("user").path("avatarUrl").isNull()).isTrue();
        assertThat(member.path("totalCount").asLong()).isEqualTo(1);
        assertThat(member.path("flavors")).hasSize(8);
        assertFlavor(member, "FLAVOR_2", 1, "5.0");

        JsonNode empty = memberBody(body, 30);
        assertThat(empty.path("totalCount").asLong()).isZero();
        assertThat(empty.path("flavors")).hasSize(8);
        assertEmptyFlavor(empty, "FLAVOR_1");
    }

    private JsonNode memberBody(JsonNode body, long userId) {
        return StreamSupport.stream(body.path("members").spliterator(), false)
                .filter(item -> item.path("user").path("userId").asLong() == userId)
                .findFirst()
                .orElseThrow();
    }

    private void assertFlavor(
            JsonNode member, String flavorCode, long popcornCount, String averageScore) {
        JsonNode flavor = flavorBody(member, flavorCode);
        assertThat(flavor.path("popcornCount").asLong()).isEqualTo(popcornCount);
        assertThat(flavor.path("averageScore").decimalValue()).isEqualByComparingTo(averageScore);
    }

    private void assertEmptyFlavor(JsonNode member, String flavorCode) {
        JsonNode flavor = flavorBody(member, flavorCode);
        assertThat(flavor.path("popcornCount").asLong()).isZero();
        assertThat(flavor.path("averageScore").isNull()).isTrue();
    }

    private JsonNode flavorBody(JsonNode member, String flavorCode) {
        return StreamSupport.stream(member.path("flavors").spliterator(), false)
                .filter(item -> item.path("flavorCode").asText().equals(flavorCode))
                .findFirst()
                .orElseThrow();
    }

    private String path(long partyId) {
        return "/api/v1/parties/" + partyId + "/popcorn";
    }

    private String bearer(long userId) {
        return "Bearer " + tokens.createAccessToken(userId);
    }

    private void user(long id, String nickname, String visibility) {
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,visibility,status)"
                        + " VALUES (?,?,?,DATE '2000-01-01','OTHER',?,'ACTIVE')",
                id,
                nickname + "@example.test",
                nickname,
                visibility);
    }

    private void flavor(long id, String code, int sortOrder) {
        jdbc.update(
                "INSERT INTO popcorn_flavors"
                        + " (id,code,display_name,color,description,sort_order,cluster_id,representative_keywords)"
                        + " VALUES (?,?,?,'#000000','테스트 맛 설명',?,?,CAST('[]' AS jsonb))",
                id,
                code,
                code,
                sortOrder,
                sortOrder - 1);
    }

    private long party(long owner, String status) {
        long id =
                jdbc.queryForObject(
                        "INSERT INTO parties(name,owner_id,status,created_at,closed_reason,closed_at)"
                                + " VALUES ('party',?,?,?,?,?) RETURNING id",
                        Long.class,
                        owner,
                        status,
                        Timestamp.from(CREATED_AT),
                        status.equals("CLOSED") ? "OWNER_CLOSED" : null,
                        status.equals("CLOSED") ? Timestamp.from(CREATED_AT.plusSeconds(1)) : null);
        member(id, owner, "OWNER", CREATED_AT);
        return id;
    }

    private void member(long party, long user, String role, Instant joinedAt) {
        jdbc.update(
                "INSERT INTO party_members(party_id,user_id,role,joined_at) VALUES (?,?,?,?)",
                party,
                user,
                role,
                Timestamp.from(joinedAt));
    }

    private void movie(long id, Long flavorId) {
        jdbc.update(
                "INSERT INTO movies(id,tmdb_id,title,representative_flavor_id) VALUES (?,?,?,?)",
                id,
                id,
                "movie" + id,
                flavorId);
    }

    private void rating(long userId, long movieId, String score) {
        jdbc.update(
                "INSERT INTO ratings(user_id,movie_id,score,created_at,updated_at) VALUES (?,?,?,?,?)",
                userId,
                movieId,
                new BigDecimal(score),
                Timestamp.from(CREATED_AT),
                Timestamp.from(CREATED_AT));
    }
}
