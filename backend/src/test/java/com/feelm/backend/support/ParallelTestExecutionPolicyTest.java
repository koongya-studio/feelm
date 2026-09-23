package com.feelm.backend.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ParallelTestExecutionPolicyTest {

    private static final Path TEST_SOURCE_ROOT = Path.of("src", "test", "java");
    private static final String SPRING_BOOT_TEST = "@Spring" + "BootTest";
    private static final String SHARED_RUNTIME = "@Shared" + "TestRuntime";
    private static final String OUTPUT_CAPTURE_EXTENSION =
            "@ExtendWith(Output" + "CaptureExtension.class)";
    private static final String OUTPUT_CAPTURE_LOCK = "@ResourceLock(\"backend-output-capture\")";
    private static final Path JUNIT_CONFIGURATION =
            Path.of("src", "test", "resources", "junit-platform.properties");
    private static final Path TEST_APPLICATION_CONFIGURATION =
            Path.of("src", "test", "resources", "application.yml");

    @Test
    void parallelConfigurationMatchesRuntimeContract() throws IOException {
        Properties configuration = new Properties();
        try (Reader reader = Files.newBufferedReader(JUNIT_CONFIGURATION)) {
            configuration.load(reader);
        }

        assertThat(configuration)
                .containsEntry("junit.jupiter.execution.parallel.enabled", "true")
                .containsEntry("junit.jupiter.execution.parallel.mode.default", "same_thread")
                .containsEntry(
                        "junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
                .containsEntry("junit.jupiter.execution.parallel.config.strategy", "fixed")
                .containsEntry("junit.jupiter.execution.parallel.config.fixed.parallelism", "2")
                .containsEntry("junit.jupiter.execution.parallel.config.fixed.max-pool-size", "2");
    }

    @Test
    void datasourcePoolMatchesParallelRuntimeContract() throws IOException {
        String configuration = Files.readString(TEST_APPLICATION_CONFIGURATION);

        assertThat(configuration).contains("maximum-pool-size: 4").contains("minimum-idle: 0");
    }

    @Test
    void springBootTestsUseSharedRuntimeLock() throws IOException {
        List<String> violations;
        try (Stream<Path> sources = Files.walk(TEST_SOURCE_ROOT)) {
            violations =
                    sources.filter(path -> path.toString().endsWith(".java"))
                            .filter(ParallelTestExecutionPolicyTest::declaresSpringBootTest)
                            .filter(path -> !usesSharedRuntimeLock(path))
                            .map(ParallelTestExecutionPolicyTest::relativePath)
                            .sorted()
                            .toList();
        }

        assertThat(violations)
                .as("Spring Boot tests must serialize access to the shared test runtime")
                .isEmpty();
    }

    @Test
    void outputCaptureTestsUseSharedOutputLock() throws IOException {
        List<String> violations;
        try (Stream<Path> sources = Files.walk(TEST_SOURCE_ROOT)) {
            violations =
                    sources.filter(path -> path.toString().endsWith(".java"))
                            .filter(ParallelTestExecutionPolicyTest::declaresOutputCapture)
                            .filter(
                                    path ->
                                            !read(path).contains(OUTPUT_CAPTURE_LOCK)
                                                    || !usesSharedRuntimeLock(path))
                            .map(ParallelTestExecutionPolicyTest::relativePath)
                            .sorted()
                            .toList();
        }

        assertThat(violations)
                .as(
                        "Output capture tests must serialize changes to the process output streams"
                                + " and must not overlap a Spring context start, which resets logging")
                .isEmpty();
    }

    private static boolean declaresSpringBootTest(Path source) {
        return read(source).contains(SPRING_BOOT_TEST);
    }

    private static boolean declaresOutputCapture(Path source) {
        return read(source).contains(OUTPUT_CAPTURE_EXTENSION);
    }

    private static boolean usesSharedRuntimeLock(Path source) {
        String text = read(source);
        return text.contains(SHARED_RUNTIME) || text.contains("extends SharedPostgresContractTest");
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
