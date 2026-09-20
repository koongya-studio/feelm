package com.feelm.backend.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 마이그레이션 테스트가 한 PostgreSQL 서버를 공유하면서 database 단위 격리를 유지하도록 지원한다.
 *
 * <p>{@code @Container}를 사용하면 테스트 클래스마다 컨테이너 생명주기가 끝나므로 서버는 JVM에서 직접 한 번 기동한다. 각 테스트 클래스는 고유한
 * database를 사용하고 완료 후 database만 제거한다. PostgreSQL 서버와 Ryuk 컨테이너는 테스트 JVM 종료 시 Testcontainers가 정리한다.
 */
final class MigrationPostgresSupport {

    private static final String USERNAME = "feelm";
    private static final String PASSWORD = "feelm";
    private static final Pattern LABEL = Pattern.compile("[a-z][a-z0-9_]{0,30}");
    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();
    private static final Map<String, MigrationTestDatabase> TEMPLATES = new HashMap<>();

    private static final PostgreSQLContainer<?> POSTGRES = startPostgres();

    private MigrationPostgresSupport() {}

    static MigrationTestDatabase database(String label) {
        return database(label, null);
    }

    /** 이전 버전의 fixture만 재사용하며 각 호출자는 독립된 DB에서 대상 migration을 실행한다. */
    static synchronized MigrationTestDatabase databaseAtVersion(String label, String version) {
        if (!LABEL.matcher(label).matches() || !version.matches("[0-9]{1,20}")) {
            throw new IllegalArgumentException("Invalid migration fixture label or version");
        }
        MigrationTestDatabase template = TEMPLATES.get(version);
        if (template == null) {
            template = database("template");
            try {
                Flyway.configure()
                        .dataSource(template.getJdbcUrl(), USERNAME, PASSWORD)
                        .target(version)
                        .load()
                        .migrate();
                // Flyway의 연결이 닫힌 뒤 봉인한다. 복제 원본은 어떤 테스트에도 반환하지 않는다.
                try (Connection connection = adminConnection();
                        Statement statement = connection.createStatement()) {
                    statement.execute(
                            "ALTER DATABASE "
                                    + statement.enquoteIdentifier(template.databaseName, true)
                                    + " ALLOW_CONNECTIONS false");
                }
                TEMPLATES.put(version, template);
            } catch (RuntimeException | SQLException exception) {
                try {
                    template.close();
                } catch (RuntimeException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
                throw new IllegalStateException("Failed to prepare migration fixture", exception);
            }
        }
        return database(label, template.databaseName);
    }

    private static MigrationTestDatabase database(String label, String templateName) {
        if (!LABEL.matcher(label).matches()) {
            throw new IllegalArgumentException("Invalid migration test database label: " + label);
        }
        String databaseName = "feelm_" + label + "_" + DATABASE_SEQUENCE.incrementAndGet();
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {
            String templateClause =
                    templateName == null
                            ? ""
                            : " TEMPLATE " + statement.enquoteIdentifier(templateName, true);
            statement.execute(
                    "CREATE DATABASE "
                            + statement.enquoteIdentifier(databaseName, true)
                            + templateClause);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to create migration test database", exception);
        }
        return new MigrationTestDatabase(databaseName, databaseUrl(databaseName));
    }

    static void dropDatabase(String databaseName) {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "DROP DATABASE "
                            + statement.enquoteIdentifier(databaseName, true)
                            + " WITH (FORCE)");
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to drop migration test database", exception);
        }
    }

    private static PostgreSQLContainer<?> startPostgres() {
        PostgreSQLContainer<?> postgres =
                new PostgreSQLContainer<>(
                                DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-bookworm")
                                        .asCompatibleSubstituteFor("postgres"))
                        .withDatabaseName("feelm")
                        .withUsername(USERNAME)
                        .withPassword(PASSWORD)
                        .withEnv("POSTGRES_INITDB_ARGS", "--locale=C.UTF-8");
        postgres.start();
        return postgres;
    }

    private static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), USERNAME, PASSWORD);
    }

    private static String databaseUrl(String databaseName) {
        String adminUrl = POSTGRES.getJdbcUrl();
        int queryStart = adminUrl.indexOf('?');
        int pathEnd = queryStart >= 0 ? queryStart : adminUrl.length();
        int databaseStart = adminUrl.lastIndexOf('/', pathEnd - 1) + 1;
        if (databaseStart <= 0) {
            throw new IllegalStateException("Unexpected PostgreSQL JDBC URL");
        }
        return adminUrl.substring(0, databaseStart) + databaseName + adminUrl.substring(pathEnd);
    }

    static final class MigrationTestDatabase implements AutoCloseable {

        private final String databaseName;
        private final String jdbcUrl;
        private final AtomicBoolean closed = new AtomicBoolean();

        private MigrationTestDatabase(String databaseName, String jdbcUrl) {
            this.databaseName = databaseName;
            this.jdbcUrl = jdbcUrl;
        }

        String getJdbcUrl() {
            return jdbcUrl;
        }

        String getUsername() {
            return USERNAME;
        }

        String getPassword() {
            return PASSWORD;
        }

        Connection connect() throws SQLException {
            return DriverManager.getConnection(jdbcUrl, USERNAME, PASSWORD);
        }

        Flyway flyway() {
            return Flyway.configure().dataSource(jdbcUrl, USERNAME, PASSWORD).load();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                dropDatabase(databaseName);
            }
        }
    }
}
