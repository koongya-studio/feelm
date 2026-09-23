package com.feelm.backend.support;

import com.feelm.backend.domain.auth.PasswordResetTokenIssuer;
import com.feelm.backend.domain.auth.RefreshTokenService;
import com.feelm.backend.domain.ott.repository.OttRepository;
import com.feelm.backend.domain.recommendation.repository.RecommendationDismissRepository;
import com.feelm.backend.domain.report.repository.ReportImageRepository;
import com.feelm.backend.domain.report.service.ReportImageLockManager;
import com.feelm.backend.domain.report.service.ReportImageStorage;
import com.feelm.backend.domain.user.repository.UserBlockRepository;
import com.feelm.backend.infrastructure.kafka.KafkaEventPublisher;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL 기반 계약 테스트가 테스트 JVM 동안 하나의 서버와 안정적인 datasource를 공유하게 한다.
 *
 * <p>{@code @Container}로 등록하지 않고 직접 한 번 시작한다. JUnit 클래스 단위 종료로 매핑 포트가 바뀌지 않으므로 동일한 Spring 설정을 사용하는
 * 테스트 클래스는 ApplicationContext도 재사용할 수 있다. 컨테이너 정리는 JVM 종료 시 Testcontainers의 Ryuk에 맡긴다.
 *
 * <p>ApplicationContext 수를 줄이기 위해 MockMvc, 공유 Redis 연결과 아래 spy를 모든 하위 클래스에 같은 조합으로 둔다. 하위 클래스가 이 중
 * 하나를 다시 선언하거나 다른 조합을 쓰면 context가 갈라진다. spy는 기본 동작이 실제 빈과 같고 테스트마다 초기화되므로, 필요한 하위 클래스는 같은 타입을
 * {@code @Autowired}로 받아 stub한다.
 */
@SharedTestRuntime
@AutoConfigureMockMvc
@TestPropertySource(properties = "feelm.auth.mock.enabled=false")
public abstract class SharedPostgresContractTest {

    private static final PostgreSQLContainer<?> POSTGRES = startPostgres();

    @ServiceConnection static final RedisContainer REDIS = SharedRedisContainer.instance();

    @MockitoSpyBean private KafkaEventPublisher kafkaEventPublisherSpy;
    @MockitoSpyBean private OttRepository ottRepositorySpy;
    @MockitoSpyBean private PasswordResetTokenIssuer passwordResetTokenIssuerSpy;
    @MockitoSpyBean private RecommendationDismissRepository recommendationDismissRepositorySpy;
    @MockitoSpyBean private RefreshTokenService refreshTokenServiceSpy;
    @MockitoSpyBean private ReportImageLockManager reportImageLockManagerSpy;
    @MockitoSpyBean private ReportImageRepository reportImageRepositorySpy;
    @MockitoSpyBean private ReportImageStorage reportImageStorageSpy;
    @MockitoSpyBean private UserBlockRepository userBlockRepositorySpy;

    @Autowired private StringRedisTemplate sharedRedisTemplate;

    @DynamicPropertySource
    protected static void sharedPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    /** 이전 클래스가 남긴 요청 제한·캐시 키를 보지 않도록 테스트마다 공유 Redis를 비운다. */
    @BeforeEach
    void clearSharedRedis() {
        SharedRedisState.clear(sharedRedisTemplate);
    }

    private static PostgreSQLContainer<?> startPostgres() {
        PostgreSQLContainer<?> postgres =
                new PostgreSQLContainer<>(
                                DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-bookworm")
                                        .asCompatibleSubstituteFor("postgres"))
                        .withEnv("POSTGRES_INITDB_ARGS", "--locale=C.UTF-8");
        postgres.start();
        return postgres;
    }
}
