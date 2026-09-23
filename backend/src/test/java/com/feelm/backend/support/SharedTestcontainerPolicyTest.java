package com.feelm.backend.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.domain.auth.PasswordResetTokenIssuer;
import com.feelm.backend.domain.auth.RefreshTokenService;
import com.feelm.backend.domain.ott.repository.OttRepository;
import com.feelm.backend.domain.recommendation.repository.RecommendationDismissRepository;
import com.feelm.backend.domain.report.repository.ReportImageRepository;
import com.feelm.backend.domain.report.service.ReportImageLockManager;
import com.feelm.backend.domain.report.service.ReportImageStorage;
import com.feelm.backend.domain.user.repository.UserBlockRepository;
import com.feelm.backend.infrastructure.kafka.KafkaEventPublisher;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class SharedTestcontainerPolicyTest {

    private static final Path TEST_SOURCE_ROOT = Path.of("src", "test", "java");
    private static final String POSTGRES_CONTAINER_CREATION = "new " + "PostgreSQLContainer";
    private static final String REDIS_CONTAINER_CREATION = "new " + "RedisContainer";
    private static final String SHARED_POSTGRES_BASE = "extends SharedPostgresContractTest";
    private static final String AUTH_MOCK_DISABLED = "feelm.auth.mock.enabled=false";
    private static final String NONEXISTENT_SCHEDULING_FLAG = "spring.task.scheduling.enabled";
    private static final Set<Class<?>> SHARED_SPY_TYPES =
            Set.of(
                    KafkaEventPublisher.class,
                    OttRepository.class,
                    PasswordResetTokenIssuer.class,
                    RecommendationDismissRepository.class,
                    RefreshTokenService.class,
                    ReportImageLockManager.class,
                    ReportImageRepository.class,
                    ReportImageStorage.class,
                    UserBlockRepository.class);
    private static final Path SHARED_POSTGRES_SUPPORT =
            TEST_SOURCE_ROOT.resolve("com/feelm/backend/support/SharedPostgresContractTest.java");
    private static final Path POLICY_SOURCE =
            TEST_SOURCE_ROOT.resolve(
                    "com/feelm/backend/support/SharedTestcontainerPolicyTest.java");
    private static final Set<String> DIRECT_CONTAINER_ALLOWLIST =
            Set.of(
                    "com/feelm/backend/migration/MigrationPostgresSupport.java",
                    "com/feelm/backend/support/SharedPostgresContractTest.java",
                    "com/feelm/backend/support/SharedRedisContainer.java");

    @Test
    void testcontainersAreCreatedOnlyBySharedSupport() throws IOException {
        List<String> violations;
        try (Stream<Path> sources = Files.walk(TEST_SOURCE_ROOT)) {
            violations =
                    sources.filter(path -> path.toString().endsWith(".java"))
                            .filter(
                                    path ->
                                            !DIRECT_CONTAINER_ALLOWLIST.contains(
                                                    relativePath(path)))
                            .filter(SharedTestcontainerPolicyTest::createsContainer)
                            .map(SharedTestcontainerPolicyTest::relativePath)
                            .sorted()
                            .toList();
        }

        assertThat(violations)
                .as("Use the shared test container support instead of creating a container")
                .isEmpty();
    }

    @Test
    void sharedPostgresAuthModeIsConfiguredOnlyBySharedSupport() throws IOException {
        assertThat(Files.readString(SHARED_POSTGRES_SUPPORT)).contains(AUTH_MOCK_DISABLED);

        List<String> violations;
        try (Stream<Path> sources = Files.walk(TEST_SOURCE_ROOT)) {
            violations =
                    sources.filter(path -> path.toString().endsWith(".java"))
                            .filter(path -> !path.equals(SHARED_POSTGRES_SUPPORT))
                            .filter(path -> !path.equals(POLICY_SOURCE))
                            .filter(
                                    path -> {
                                        String text = read(path);
                                        return text.contains(SHARED_POSTGRES_BASE)
                                                && text.contains(AUTH_MOCK_DISABLED);
                                    })
                            .map(SharedTestcontainerPolicyTest::relativePath)
                            .sorted()
                            .toList();
        }

        assertThat(violations)
                .as("Shared PostgreSQL tests must inherit the common auth mode")
                .isEmpty();
    }

    @Test
    void sharedPostgresTestsDoNotRedeclareSharedContextSettings() throws IOException {
        List<String> violations;
        try (Stream<Path> sources = Files.walk(TEST_SOURCE_ROOT)) {
            violations =
                    sources.filter(path -> path.toString().endsWith(".java"))
                            .map(SharedTestcontainerPolicyTest::loadClass)
                            .filter(type -> type != SharedPostgresContractTest.class)
                            .filter(SharedPostgresContractTest.class::isAssignableFrom)
                            .flatMap(SharedTestcontainerPolicyTest::sharedContextRedeclarations)
                            .sorted()
                            .toList();
        }

        assertThat(violations)
                .as(
                        "Shared PostgreSQL tests must inherit MockMvc, Redis and the shared spies"
                                + " so that they reuse one ApplicationContext")
                .isEmpty();
    }

    /** 상위 클래스가 이미 제공하는 context 설정을 하위 클래스가 다시 선언한 곳. 중간 지원 클래스도 검사한다. */
    private static Stream<String> sharedContextRedeclarations(Class<?> type) {
        Stream.Builder<String> found = Stream.builder();
        AutoConfigureMockMvc mockMvc = type.getDeclaredAnnotation(AutoConfigureMockMvc.class);
        if (mockMvc != null && mockMvc.addFilters() && mockMvc.print() == MockMvcPrint.DEFAULT) {
            found.add(type.getName() + ": @AutoConfigureMockMvc");
        }
        SpringBootTest bootTest = type.getDeclaredAnnotation(SpringBootTest.class);
        if (bootTest != null
                && Arrays.stream(bootTest.properties())
                        .anyMatch(property -> property.startsWith(NONEXISTENT_SCHEDULING_FLAG))) {
            found.add(type.getName() + ": " + NONEXISTENT_SCHEDULING_FLAG);
        }
        for (Field field : type.getDeclaredFields()) {
            if (field.isAnnotationPresent(ServiceConnection.class)) {
                found.add(type.getName() + "." + field.getName() + ": @ServiceConnection");
            }
            if (field.isAnnotationPresent(MockitoSpyBean.class)
                    && SHARED_SPY_TYPES.contains(field.getType())) {
                found.add(type.getName() + "." + field.getName() + ": shared @MockitoSpyBean");
            }
        }
        return found.build();
    }

    private static Class<?> loadClass(Path source) {
        String path = relativePath(source);
        String name = path.substring(0, path.length() - ".java".length()).replace('/', '.');
        try {
            return Class.forName(name, false, SharedTestcontainerPolicyTest.class.getClassLoader());
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Failed to load test class: " + name, exception);
        }
    }

    private static boolean createsContainer(Path source) {
        String text = read(source);
        return text.contains(POSTGRES_CONTAINER_CREATION)
                || text.contains(REDIS_CONTAINER_CREATION);
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to inspect test source: " + source, exception);
        }
    }

    private static String relativePath(Path source) {
        return TEST_SOURCE_ROOT.relativize(source).toString().replace('\\', '/');
    }
}
