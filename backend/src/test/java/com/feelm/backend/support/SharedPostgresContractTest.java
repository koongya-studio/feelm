package com.feelm.backend.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL 기반 계약 테스트가 테스트 JVM 동안 하나의 서버와 안정적인 datasource를 공유하게 한다.
 *
 * <p>{@code @Container}로 등록하지 않고 직접 한 번 시작한다. JUnit 클래스 단위 종료로 매핑 포트가 바뀌지 않으므로 동일한 Spring 설정을 사용하는
 * 테스트 클래스는 ApplicationContext도 재사용할 수 있다. 컨테이너 정리는 JVM 종료 시 Testcontainers의 Ryuk에 맡긴다.
 */
@SharedTestRuntime
public abstract class SharedPostgresContractTest {

    private static final PostgreSQLContainer<?> POSTGRES = startPostgres();

    @DynamicPropertySource
    protected static void sharedPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
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
