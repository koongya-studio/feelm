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

    private static boolean createsContainer(Path source) {
        try {
            String text = Files.readString(source);
            return text.contains(POSTGRES_CONTAINER_CREATION)
                    || text.contains(REDIS_CONTAINER_CREATION);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to inspect test source: " + source, exception);
        }
    }

    private static String relativePath(Path source) {
        return TEST_SOURCE_ROOT.relativize(source).toString().replace('\\', '/');
    }
}
