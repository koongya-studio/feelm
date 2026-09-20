package com.feelm.backend.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SharedTestcontainerPolicyTest {

    private static final Path TEST_SOURCE_ROOT = Path.of("src", "test", "java");
    private static final String POSTGRES_CONTAINER_CREATION = "new " + "PostgreSQLContainer";
    private static final String REDIS_CONTAINER_CREATION = "new " + "RedisContainer";
    private static final String SHARED_POSTGRES_BASE = "extends SharedPostgresContractTest";
    private static final String AUTH_MOCK_DISABLED = "feelm.auth.mock.enabled=false";
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
