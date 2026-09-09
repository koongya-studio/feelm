package com.feelm.backend.domain.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.TokenProvider;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** API-049·050: 실제 마이그레이션과 PostgreSQL을 사용하는 HTTP 계약 테스트. */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@AutoConfigureMockMvc
@Testcontainers
class PartyApiContractTest {
    private static final String PATH = "/api/v1/parties";
    private static final Instant CREATED_AT = Instant.parse("2026-09-01T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-bookworm")
                            .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;
    @Autowired ObjectMapper json;

    @BeforeEach
    void fixtures() {
        // 이 클래스 전용 일회성 컨테이너에만 적용한다. 로컬 개발 DB를 사용하지 않는다.
        jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
        for (long id : new long[] {10, 20, 30, 40}) {
            jdbc.update(
                    "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                            + " VALUES (?,?,?,DATE '2000-01-01','OTHER','ACTIVE')",
                    id,
                    "party" + id + "@example.test",
                    "member" + id);
        }
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(
                        post(PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"party\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createsPartyAndOwnerMembership() throws Exception {
        JsonNode body =
                body(
                        create("{\"name\":\"금요일 영화 모임\"}")
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.name").value("금요일 영화 모임"))
                                .andExpect(jsonPath("$.status").value("ACTIVE"))
                                .andExpect(jsonPath("$.owner.userId").value(10))
                                .andExpect(jsonPath("$.owner.nickname").value("member10"))
                                .andExpect(jsonPath("$.maxMembers").value(4))
                                .andExpect(jsonPath("$.members", hasSize(1)))
                                .andExpect(jsonPath("$.members[0].userId").value(10))
                                .andExpect(jsonPath("$.members[0].nickname").value("member10"))
                                .andExpect(jsonPath("$.members[0].role").value("OWNER")));
        long id = body.get("partyId").asLong();
        assertThat(id).isPositive();
        assertThat(Instant.parse(body.get("createdAt").asText())).isNotNull();
        assertThat(Instant.parse(body.get("members").get(0).get("joinedAt").asText())).isNotNull();
        assertThat(jdbc.queryForObject("SELECT owner_id FROM parties WHERE id=?", Long.class, id))
                .isEqualTo(10);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM party_members WHERE party_id=? AND user_id=10 AND role='OWNER'",
                                Long.class,
                                id))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"name\":null}",
                "{\"name\":\"\"}",
                "{\"name\":\"                     \"}",
                "{\"name\":\"123456789012345678901\"}",
                "{"
            })
    void rejectsInvalidNameWithoutSaving(String request) throws Exception {
        create(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parties", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM party_members", Long.class)).isZero();
    }

    @Test
    void acceptsTwentyCharacterName() throws Exception {
        create("{\"name\":\"12345678901234567890\"}").andExpect(status().isCreated());
    }

    @Test
    void rollsBackPartyWhenOwnerMembershipCannotBeSaved() throws Exception {
        // 저장소를 mock하지 않고 테스트 전용 DB 제약으로 두 번째 저장을 실패시킨다.
        jdbc.execute(
                "ALTER TABLE party_members ADD CONSTRAINT test_reject_owner CHECK (user_id <> 10)");
        try {
            create("{\"name\":\"rollback\"}").andExpect(status().is5xxServerError());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM parties", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM party_members", Long.class))
                    .isZero();
        } finally {
            jdbc.execute("ALTER TABLE party_members DROP CONSTRAINT test_reject_owner");
        }
    }

    @Test
    void allowsCreationBeyondFiveActiveMemberships() throws Exception {
        for (int i = 0; i < 6; i++) {
            member(party(20, "ACTIVE"), 10);
            party(10, "ACTIVE");
        }
        create("{\"name\":\"another party\"}").andExpect(status().isCreated());
        assertThat(activeCount()).isEqualTo(13);
    }

    @Test
    void listsOnlyCurrentMembershipAndPendingInvitations() throws Exception {
        long own = party(10, "ACTIVE");
        member(own, 30);
        invitation(own, 40, "PENDING");
        invitation(own, 20, "DECLINED");
        invitation(own, 30, "ACCEPTED");
        long joined = party(20, "ACTIVE");
        member(joined, 10);
        invitation(party(20, "ACTIVE"), 10, "PENDING");
        long closed = party(10, "CLOSED");
        list().andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].partyId").value(joined))
                .andExpect(jsonPath("$.items[0].pendingInvitations", hasSize(0)))
                .andExpect(jsonPath("$.items[1].partyId").value(own))
                .andExpect(jsonPath("$.items[1].members", hasSize(2)))
                .andExpect(jsonPath("$.items[1].owner.nickname").value("member10"))
                .andExpect(jsonPath("$.items[1].pendingInvitations", hasSize(1)))
                .andExpect(jsonPath("$.items[1].pendingInvitations[0].invitee.userId").value(40))
                .andExpect(
                        jsonPath("$.items[1].pendingInvitations[0].invitee.nickname")
                                .value("member40"));
        mvc.perform(get(PATH).param("status", "CLOSED").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].partyId").value(closed));
    }

    @Test
    void returnsEmptyCursorPage() throws Exception {
        list().andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    @Test
    void paginatesPartiesWithoutTruncatingMembers() throws Exception {
        long oldest = party(10, "CLOSED");
        long middle = party(10, "CLOSED");
        long newest = party(10, "CLOSED");
        member(newest, 20);
        member(newest, 30);
        member(newest, 40);
        JsonNode first =
                body(
                        mvc.perform(
                                        get(PATH)
                                                .param("status", "CLOSED")
                                                .param("size", "2")
                                                .header("Authorization", bearer()))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.items", hasSize(2)))
                                .andExpect(jsonPath("$.items[0].partyId").value(newest))
                                .andExpect(jsonPath("$.items[0].members", hasSize(4)))
                                .andExpect(jsonPath("$.items[1].partyId").value(middle))
                                .andExpect(jsonPath("$.hasNext").value(true)));
        mvc.perform(
                        get(PATH)
                                .param("status", "CLOSED")
                                .param("size", "2")
                                .param("cursor", first.get("nextCursor").asText())
                                .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].partyId").value(oldest))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    @Test
    void usesDefaultTwentyAndAcceptsMaximumFifty() throws Exception {
        for (int i = 0; i < 51; i++) party(10, "CLOSED");
        mvc.perform(get(PATH).param("status", "CLOSED").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(20)))
                .andExpect(jsonPath("$.hasNext").value(true));
        mvc.perform(
                        get(PATH)
                                .param("status", "CLOSED")
                                .param("size", "50")
                                .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(50)))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    void paginatesByCreationTimeWhenIdOrderDiffers() throws Exception {
        long newest = party(10, "CLOSED", CREATED_AT.plusSeconds(2));
        long oldest = party(10, "CLOSED", CREATED_AT);
        long middle = party(10, "CLOSED", CREATED_AT.plusSeconds(1));
        List<Long> expectedIds = List.of(newest, middle, oldest);
        String cursor = null;

        for (int page = 0; page < expectedIds.size(); page++) {
            var request =
                    get(PATH)
                            .param("status", "CLOSED")
                            .param("size", "1")
                            .header("Authorization", bearer());
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            boolean hasNext = page < expectedIds.size() - 1;
            var result =
                    mvc.perform(request)
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.items", hasSize(1)))
                            .andExpect(jsonPath("$.items[0].partyId").value(expectedIds.get(page)))
                            .andExpect(jsonPath("$.hasNext").value(hasNext));
            if (hasNext) {
                result.andExpect(jsonPath("$.nextCursor").isNotEmpty());
                cursor = body(result).get("nextCursor").asText();
            } else {
                result.andExpect(jsonPath("$.nextCursor").isEmpty());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "51", "abc"})
    void rejectsInvalidPageSize(String size) throws Exception {
        mvc.perform(get(PATH).param("size", size).header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsInvalidStatusAndCursor() throws Exception {
        for (String parameter : List.of("status", "cursor")) {
            mvc.perform(get(PATH).param(parameter, "INVALID!").header("Authorization", bearer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    private ResultActions create(String request) throws Exception {
        return mvc.perform(
                post(PATH)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request));
    }

    private ResultActions list() throws Exception {
        return mvc.perform(get(PATH).header("Authorization", bearer()));
    }

    private String bearer() {
        return "Bearer " + tokens.createAccessToken(10);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private long party(long owner, String status) {
        return party(owner, status, CREATED_AT);
    }

    private long party(long owner, String status, Instant createdAt) {
        long id =
                jdbc.queryForObject(
                        "INSERT INTO parties(name,owner_id,status,created_at,closed_reason,closed_at)"
                                + " VALUES ('party',?,?,?,?,?) RETURNING id",
                        Long.class,
                        owner,
                        status,
                        Timestamp.from(createdAt),
                        status.equals("CLOSED") ? "OWNER_CLOSED" : null,
                        status.equals("CLOSED") ? Timestamp.from(createdAt.plusSeconds(1)) : null);
        jdbc.update(
                "INSERT INTO party_members(party_id,user_id,role,joined_at) VALUES (?,?,'OWNER',?)",
                id,
                owner,
                Timestamp.from(createdAt));
        return id;
    }

    private void member(long party, long user) {
        jdbc.update(
                "INSERT INTO party_members(party_id,user_id,role,joined_at) VALUES (?,?,'MEMBER',?)",
                party,
                user,
                Timestamp.from(CREATED_AT));
    }

    private void invitation(long party, long invitee, String status) {
        long inviter =
                jdbc.queryForObject("SELECT owner_id FROM parties WHERE id=?", Long.class, party);
        jdbc.update(
                "INSERT INTO party_invitations(party_id,inviter_id,invitee_id,status,created_at,responded_at)"
                        + " VALUES (?,?,?,?,?,?)",
                party,
                inviter,
                invitee,
                status,
                Timestamp.from(CREATED_AT),
                status.equals("PENDING") ? null : Timestamp.from(CREATED_AT.plusSeconds(1)));
    }

    private long activeCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM party_members m JOIN parties p ON p.id=m.party_id"
                        + " WHERE m.user_id=10 AND p.status='ACTIVE'",
                Long.class);
    }
}
