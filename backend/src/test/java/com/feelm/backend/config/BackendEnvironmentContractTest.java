package com.feelm.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.unit.DataSize;

class BackendEnvironmentContractTest {

    private static final Pattern REQUIRED_VARIABLE = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)}");
    private static final Pattern ANY_VARIABLE = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)");
    private static final Pattern DEFAULTED_VARIABLE =
            Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*):[^}]*}");
    private static final Pattern ENV_KEY = Pattern.compile("^([A-Z][A-Z0-9_]*)=");
    private static final Pattern CONTRACT_ENTRY =
            Pattern.compile("^([A-Z][A-Z0-9_]*)=([A-Z][A-Z0-9_]*|@compose-default|@dev-default)$");
    private static final Pattern COMPOSE_ENTRY =
            Pattern.compile("^      ([A-Z][A-Z0-9_]*):\\s*(.*)$");
    private static final Pattern COMPOSE_PLACEHOLDER =
            Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)(?::([-?])[^}]*)?}");

    private final Path project = Path.of(System.getProperty("user.dir"));

    @Test
    void documentsEveryApplicationVariable() throws IOException {
        Set<String> documented = dotenvKeys(project.resolve(".env.example"));
        Set<String> referenced = new LinkedHashSet<>();
        for (Path config : applicationConfigs()) {
            Matcher matcher = ANY_VARIABLE.matcher(configText(config));
            while (matcher.find()) {
                referenced.add(matcher.group(1));
            }
        }

        assertThat(documented).containsAll(referenced);
    }

    @Test
    void mapsEveryRequiredDevVariableToDeploymentInput() throws IOException {
        Set<String> required = new LinkedHashSet<>();
        for (Path config : applicationConfigs()) {
            Matcher matcher = REQUIRED_VARIABLE.matcher(Files.readString(config));
            while (matcher.find()) {
                required.add(matcher.group(1));
            }
        }

        assertThat(deploymentContract().keySet()).containsExactlyInAnyOrderElementsOf(required);
    }

    @Test
    void defaultsEveryVariableNotInjectedByComposeInDevProfile() throws IOException {
        Set<String> defaulted = new LinkedHashSet<>();
        Matcher matcher =
                DEFAULTED_VARIABLE.matcher(
                        configText(project.resolve("src/main/resources/application-dev.yml")));
        while (matcher.find()) {
            defaulted.add(matcher.group(1));
        }
        Set<String> notInjected = new LinkedHashSet<>();
        deploymentContract()
                .forEach(
                        (target, source) -> {
                            if (source.equals("@dev-default")) {
                                notInjected.add(target);
                            }
                        });

        assertThat(defaulted).containsAll(notInjected);
        assertThat(composeEnvironment().keySet()).doesNotContainAnyElementsOf(notInjected);
    }

    @Test
    void bindsMultipartLimitsFromOperationalConfiguration() throws IOException {
        Path applicationConfig = project.resolve("src/main/resources/application.yml");
        List<PropertySource<?>> loaded =
                new YamlPropertySourceLoader()
                        .load("operational-application", new FileSystemResource(applicationConfig));
        Binder binder = new Binder(ConfigurationPropertySources.from(loaded));

        assertThat(binder.bind("spring.servlet.multipart.max-file-size", DataSize.class).get())
                .isEqualTo(DataSize.ofMegabytes(10));
        assertThat(binder.bind("spring.servlet.multipart.max-request-size", DataSize.class).get())
                .isEqualTo(DataSize.ofMegabytes(11));
    }

    @Test
    void injectsEveryContractVariableThroughReviewedComposeExpression() throws IOException {
        Map<String, String> compose = composeEnvironment();
        deploymentContract()
                .forEach(
                        (target, source) -> {
                            if (source.equals("@dev-default")) {
                                assertThat(compose).doesNotContainKey(target);
                                return;
                            }
                            assertThat(compose).containsKey(target);
                            Map<String, String> placeholders =
                                    composePlaceholders(compose.get(target));
                            if (source.equals("@compose-default")) {
                                assertThat(placeholders).containsEntry(target, "-");
                            } else {
                                assertThat(placeholders).containsEntry(source, "?");
                            }
                        });
    }

    @Test
    void invalidContractDoesNotExposeItsContents(@TempDir Path tempDir) throws IOException {
        String secret = "must-not-appear-in-output";
        Path contract = tempDir.resolve("contract.properties");
        Files.writeString(contract, "JWT_SECRET=" + secret);

        assertThatThrownBy(() -> deploymentContract(contract))
                .hasMessageContaining("line 1")
                .hasMessageNotContaining(secret);
    }

    private List<Path> applicationConfigs() {
        return List.of(
                project.resolve("src/main/resources/application.yml"),
                project.resolve("src/main/resources/application-dev.yml"));
    }

    private static String configText(Path path) throws IOException {
        StringBuilder result = new StringBuilder();
        for (String line : Files.readAllLines(path)) {
            String withoutComment = line.replaceFirst("\\s+#.*$", "");
            if (!withoutComment.stripLeading().startsWith("#")) {
                result.append(withoutComment).append('\n');
            }
        }
        return result.toString();
    }

    private Map<String, String> composeEnvironment() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        boolean inBackend = false;
        boolean inEnvironment = false;
        for (String line : Files.readAllLines(project.resolve("../infra/compose/service.yml"))) {
            if (line.matches("^  [a-zA-Z0-9_-]+:$")) {
                inBackend = line.equals("  backend:");
                inEnvironment = false;
            } else if (inBackend && line.equals("    environment:")) {
                inEnvironment = true;
            } else if (inEnvironment) {
                Matcher matcher = COMPOSE_ENTRY.matcher(line);
                if (matcher.matches()) {
                    entries.put(matcher.group(1), matcher.group(2));
                } else if (!line.isEmpty() && !line.startsWith("      ")) {
                    break;
                }
            }
        }
        assertThat(entries).as("Compose backend environment").isNotEmpty();
        return entries;
    }

    private static Map<String, String> composePlaceholders(String expression) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        Matcher matcher = COMPOSE_PLACEHOLDER.matcher(expression);
        while (matcher.find()) {
            placeholders.put(matcher.group(1), matcher.group(2) == null ? "" : matcher.group(2));
        }
        return placeholders;
    }

    private static Set<String> dotenvKeys(Path path) throws IOException {
        Set<String> keys = new LinkedHashSet<>();
        for (String line : Files.readAllLines(path)) {
            Matcher matcher = ENV_KEY.matcher(line);
            if (matcher.find()) {
                keys.add(matcher.group(1));
            }
        }
        return keys;
    }

    private Map<String, String> deploymentContract() throws IOException {
        return deploymentContract(project.resolve("config/deployment-environment.properties"));
    }

    private static Map<String, String> deploymentContract(Path path) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        int lineNumber = 0;
        for (String rawLine : Files.readAllLines(path)) {
            lineNumber++;
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            Matcher matcher = CONTRACT_ENTRY.matcher(line);
            assertThat(matcher.matches())
                    .as("valid deployment contract entry at line %s", lineNumber)
                    .isTrue();
            assertThat(entries.put(matcher.group(1), matcher.group(2)))
                    .as("duplicate deployment contract key: %s", matcher.group(1))
                    .isNull();
        }
        return entries;
    }
}
