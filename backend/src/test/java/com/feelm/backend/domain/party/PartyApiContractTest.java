package com.feelm.backend.domain.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** API-049·050: 실제 마이그레이션과 PostgreSQL을 사용하는 HTTP 계약 테스트. */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@AutoConfigureMockMvc
class PartyApiContractTest extends SharedPostgresContractTest {
    private static final String PATH = "/api/v1/parties";
    private static final Instant CREATED_AT = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void fixtures() {
        // 공유 테스트 DB만 초기화한다. 로컬 개발 DB를 사용하지 않는다.
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
    void allowsTenthActivePartyButRejectsEleventh() throws Exception {
        for (int i = 0; i < 9; i++) {
            member(party(20, "ACTIVE"), 10);
        }
        create("{\"name\":\"tenth party\"}").andExpect(status().isCreated());
        assertThat(activeCount()).isEqualTo(10);

        create("{\"name\":\"eleventh party\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_PARTY_LIMIT_REACHED"));
        assertThat(activeCount()).isEqualTo(10);
    }

    @Test
    void serializesConcurrentCreationAtActivePartyLimit() throws Exception {
        for (int i = 0; i < 9; i++) {
            member(party(20, "ACTIVE"), 10);
        }
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<CreateResult> first =
                    executor.submit(() -> createAfter(start, "concurrent one"));
            Future<CreateResult> second =
                    executor.submit(() -> createAfter(start, "concurrent two"));
            start.countDown();

            List<CreateResult> results = List.of(first.get(), second.get());
            assertThat(results)
                    .extracting(CreateResult::status)
                    .containsExactlyInAnyOrder(201, 409);
            assertThat(results.stream().filter(result -> result.status() == 409).findFirst())
                    .hasValueSatisfying(
                            result ->
                                    assertThat(result.code())
                                            .isEqualTo("ACTIVE_PARTY_LIMIT_REACHED"));
        }
        assertThat(activeCount()).isEqualTo(10);
    }

    @Test
    void waitsUntilUserRowLockIsReleased() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> lockHolder =
                    executor.submit(
                            () ->
                                    new TransactionTemplate(transactionManager)
                                            .executeWithoutResult(
                                                    ignored -> {
                                                        jdbc.queryForObject(
                                                                "SELECT id FROM users WHERE id=? FOR UPDATE",
                                                                Long.class,
                                                                10);
                                                        locked.countDown();
                                                        await(release);
                                                    }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<CreateResult> blockedRequest =
                    executor.submit(() -> performCreate("locked user party"));
            try {
                assertThatThrownBy(() -> blockedRequest.get(300, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            lockHolder.get(5, TimeUnit.SECONDS);
            assertThat(blockedRequest.get(5, TimeUnit.SECONDS).status()).isEqualTo(201);
        }
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

    /**
     * 형식은 맞지만 커서가 담을 수 있는 범위를 벗어난 시각. 상한을 넘는 값은 질의로 내려가면 드라이버나 DB가 거절해 클라이언트 입력이 500이 되고, 하한 미만은
     * 저장된 생성 시각일 수 없다.
     */
    @Test
    void rejectsUnstorableCursorInstant() throws Exception {
        String[] cursors = {
            forgedCursor("+999999999-12-31T23:59:59Z:1"),
            forgedCursor("+0500000-01-01T00:00:00Z:1"),
            forgedCursor("1900-01-01T00:00:00Z:1")
        };

        for (String cursor : cursors) {
            mvc.perform(get(PATH).param("cursor", cursor).header("Authorization", bearer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    /** 서버가 만들지 않은 커서를 흉내낸다. 인코딩 형식만 맞추고 값은 임의로 넣는다. */
    private String forgedCursor(String raw) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private ResultActions create(String request) throws Exception {
        return mvc.perform(
                post(PATH)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request));
    }

    private CreateResult createAfter(CountDownLatch start, String name) throws Exception {
        start.await();
        return performCreate(name);
    }

    private CreateResult performCreate(String name) throws Exception {
        var response =
                mvc.perform(
                                post(PATH)
                                        .header("Authorization", bearer())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"name\":\"" + name + "\"}"))
                        .andReturn()
                        .getResponse();
        String code =
                response.getContentAsString().isBlank()
                        ? null
                        : json.readTree(response.getContentAsString()).path("code").asText(null);
        return new CreateResult(response.getStatus(), code);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for test latch", exception);
        }
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

    private record CreateResult(int status, String code) {}
}
