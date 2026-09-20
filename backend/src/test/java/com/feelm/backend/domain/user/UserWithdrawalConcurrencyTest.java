package com.feelm.backend.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import com.feelm.backend.support.SharedRedisContainer;
import com.redis.testcontainers.RedisContainer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * API-013 회원 탈퇴가 같은 계정의 다른 쓰기와 교차 실행될 때의 계약을 검증한다.
 *
 * <p>실제 PostgreSQL과 마이그레이션 위에서 돌린다. 계정 행 잠금({@code SELECT ... FOR UPDATE})의 대기 동작이 검증 대상이고, 익명화가
 * 지우는 {@code birth_date}·{@code gender}에는 마이그레이션에만 있는 상태 기반 CHECK 제약이 걸려 있어(V202609021650,
 * V202609051000) Entity로 만든 H2 스키마로는 둘 다 확인할 수 없다. 내 구독 OTT 계약 테스트가 같은 방식을 쓴다.
 */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@AutoConfigureMockMvc
@DisplayName("회원 탈퇴 동시성 — API-013")
class UserWithdrawalConcurrencyTest extends SharedPostgresContractTest {

    private static final String ME_PATH = "/api/v1/users/me";
    private static final long USER_ID = 10;
    private static final String PASSWORD = "P@ssw0rd1";

    /**
     * 탈퇴가 Refresh Token을 폐기하므로 Redis도 필요하다. PostgreSQL은 상위 클래스가 공유한다.
     *
     * <p>{@code @SpringBootTest}의 속성 집합을 {@code MovieFilterOptionsContractTest}와 똑같이 맞춘 이유는 Spring
     * Context를 공유하기 위해서다. 속성이 하나라도 다르면 컨텍스트가 따로 만들어지고 커넥션 풀도 따로 생기는데, 공유 PostgreSQL 한 대에 풀이 쌓이면
     * 클라이언트 한도를 넘겨 무관한 테스트의 컨텍스트 생성이 실패한다. 인증 mock 비활성화는 공유 PostgreSQL 지원 클래스가 모든 하위 테스트에 공통으로
     * 적용한다.
     */
    @ServiceConnection static final RedisContainer REDIS = SharedRedisContainer.instance();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired DataSource dataSource;

    private ExecutorService executor;

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
        jdbc.update(
                """
                insert into users (id, email, password_hash, nickname, birth_date, gender,
                                   visibility, status)
                values (?, 'active@example.com', ?, '탈퇴예정', date '1999-03-14', 'FEMALE',
                        'PUBLIC', 'ACTIVE')
                """,
                USER_ID,
                passwordEncoder.encode(PASSWORD));
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopExecutor() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("T11 프로필 수정과 동시에 들어와도 치환한 식별자가 되살아나지 않는다")
    void concurrentWithdrawAndProfileUpdateDoesNotRestoreIdentifiers() throws Exception {
        List<Integer> statuses =
                runContended(
                        () -> withdrawStatus(PASSWORD),
                        () ->
                                mvc.perform(
                                                patch(ME_PATH)
                                                        .header(HttpHeaders.AUTHORIZATION, bearer())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content("{\"nickname\":\"되살아난닉네임\"}"))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus());

        // 먼저 커밋한 쪽에 따라 프로필 수정은 200이거나 401이다. 탈퇴는 어느 쪽이든 성공한다.
        assertThat(statuses.get(0)).isEqualTo(204);
        assertThat(statuses.get(1)).isIn(200, 401);

        Map<String, Object> user = user();
        assertThat(user.get("status")).isEqualTo("WITHDRAWN");
        assertThat(user.get("nickname")).isEqualTo("withdrawn-" + USER_ID);
        assertThat(user.get("email")).isEqualTo("withdrawn-" + USER_ID + "@feelm.invalid");
        assertThat(user.get("visibility")).isEqualTo("PRIVATE");
        assertThat(user.get("birth_date")).isNull();
        assertThat(user.get("gender")).isNull();
    }

    @Test
    @DisplayName("T12 같은 계정의 탈퇴가 동시에 들어오면 한 번만 처리된다")
    void concurrentWithdrawRequestsWithdrawOnce() throws Exception {
        List<Integer> statuses =
                runContended(() -> withdrawStatus(PASSWORD), () -> withdrawStatus(PASSWORD));

        assertThat(statuses).containsExactlyInAnyOrder(204, 401);
        assertThat(user().get("status")).isEqualTo("WITHDRAWN");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from users where id = ? and withdrawn_at is not null",
                                Long.class,
                                USER_ID))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("T18 치환 뒤 같은 이메일·닉네임이 실제 유일 제약에서 풀린다 (D-9)")
    void releasesUniqueConstraintsAfterAnonymization() throws Exception {
        String email = (String) user().get("email");
        String nickname = (String) user().get("nickname");

        assertThat(withdrawStatus(PASSWORD)).isEqualTo(204);

        // H2에는 uk_users_email도 uix_users_nickname_normalized도 없어 계약 테스트의 재가입 성공이
        // 유일 제약을 검증하지 못한다(리뷰 B-F8). 실제 제약이 걸린 스키마에 같은 값을 다시 넣어 본다.
        jdbc.update(
                """
                insert into users (email, password_hash, nickname, birth_date, gender,
                                   visibility, status)
                values (?, 'x', ?, date '1999-03-14', 'FEMALE', 'PRIVATE', 'ACTIVE')
                """,
                email,
                nickname);

        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from users where email = ?", Long.class, email))
                .isEqualTo(1);
    }

    /**
     * 두 요청이 <b>반드시 같은 계정 행을 두고 경합하도록</b> 만든다.
     *
     * <p>출발 시각만 맞추면 앞선 요청이 이미 커밋을 끝낸 뒤 다음 요청이 시작될 수 있어, 잠금을 통째로 없애도 테스트가 통과한다(리뷰 A-F6·B-F7). 그래서
     * 테스트가 별도 커넥션으로 계정 행을 먼저 잠그고, 두 요청이 <b>둘 다 그 잠금을 기다리는 것을 확인한 뒤</b> 놓아준다. 그 순간부터 두 트랜잭션은 같은 행을
     * 두고 실제로 줄을 선다.
     *
     * @return 넘긴 순서 그대로의 응답 상태
     */
    private List<Integer> runContended(Callable<Integer> first, Callable<Integer> second)
            throws Exception {
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock =
                    blocker.prepareStatement("select id from users where id = ? for update")) {
                lock.setLong(1, USER_ID);
                lock.executeQuery();
            }

            Future<Integer> firstResult = executor.submit(first);
            Future<Integer> secondResult = executor.submit(second);
            awaitBothWaitingOnLock();
            blocker.rollback();

            return List.of(
                    firstResult.get(20, TimeUnit.SECONDS), secondResult.get(20, TimeUnit.SECONDS));
        }
    }

    /** 두 요청이 모두 행 잠금에서 대기할 때까지 기다린다. 대기하지 않으면 경합을 만들지 못한 것이므로 실패시킨다. */
    private void awaitBothWaitingOnLock() throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(15);
        while (lockWaiterCount() < 2) {
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("두 요청이 계정 행 잠금에서 대기하지 않았다");
            }
            Thread.sleep(20);
        }
    }

    private int lockWaiterCount() {
        return jdbc.queryForObject(
                "select count(*) from pg_stat_activity where wait_event_type = 'Lock'",
                Integer.class);
    }

    private int withdrawStatus(String password) throws Exception {
        return mvc.perform(
                        delete(ME_PATH)
                                .header(HttpHeaders.AUTHORIZATION, bearer())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\":\"%s\"}".formatted(password)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private String bearer() {
        return "Bearer " + tokens.createAccessToken(USER_ID);
    }

    private Map<String, Object> user() {
        return jdbc.queryForMap("select * from users where id = ?", USER_ID);
    }
}
