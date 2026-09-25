package com.feelm.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class UserFeedbackMigrationTest {

    @Test
    void withdrawalDeletesStoredFeedback() throws Exception {
        try (var database =
                MigrationPostgresSupport.databaseAtVersion("feedback", "202609251800")) {
            Flyway.configure()
                    .dataSource(
                            database.getJdbcUrl(), database.getUsername(), database.getPassword())
                    .target("202609251900")
                    .load()
                    .migrate();
            try (Connection connection = database.connect();
                    Statement statement = connection.createStatement()) {
                statement.execute(
                        """
                        INSERT INTO users(email,nickname,birth_date,gender,status,visibility)
                        VALUES ('feedback@feelm.test','피드백계정',DATE '1994-04-04','OTHER','ACTIVE','PRIVATE')
                        """);
                statement.execute(
                        """
                        INSERT INTO user_feedback(user_id,category,comment,screen_path)
                        SELECT id,'GENERAL','의견','/app/my/settings'
                        FROM users WHERE email='feedback@feelm.test'
                        """);
                statement.execute(
                        "UPDATE users SET status='WITHDRAWN' WHERE email='feedback@feelm.test'");
                try (ResultSet rows =
                        statement.executeQuery("SELECT count(*) FROM user_feedback")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isZero();
                }
            }
        }
    }
}
