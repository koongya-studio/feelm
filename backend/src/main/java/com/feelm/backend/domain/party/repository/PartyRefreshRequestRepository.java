package com.feelm.backend.domain.party.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 파티별 최신 refresh 요청을 DB 커밋과 함께 보존한다. */
@Repository
@RequiredArgsConstructor
public class PartyRefreshRequestRepository {
    private final JdbcClient jdbcClient;

    public void request(long partyId) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO party_refresh_requests (party_id) VALUES (:partyId)
                        ON CONFLICT (party_id) DO UPDATE SET
                            request_version = party_refresh_requests.request_version + 1,
                            next_attempt_at = now(), attempt_count = 0
                        """)
                .param("partyId", partyId)
                .update();
    }

    public List<Long> findDue(Instant now, int limit) {
        return jdbcClient
                .sql(
                        """
                        SELECT party_id FROM party_refresh_requests
                        WHERE next_attempt_at <= :now ORDER BY next_attempt_at, party_id LIMIT :limit
                        """)
                .param("now", time(now))
                .param("limit", limit)
                .query(Long.class)
                .list();
    }

    /** 호출자는 먼저 파티 행 잠금을 얻는다. */
    public Optional<Long> findVersionForUpdate(long partyId) {
        return jdbcClient
                .sql(
                        "SELECT request_version FROM party_refresh_requests"
                                + " WHERE party_id = :partyId FOR UPDATE")
                .param("partyId", partyId)
                .query(Long.class)
                .optional();
    }

    public void delete(long partyId) {
        jdbcClient
                .sql("DELETE FROM party_refresh_requests WHERE party_id = :partyId")
                .param("partyId", partyId)
                .update();
    }

    public void retry(long partyId, long version, Instant nextAttemptAt) {
        jdbcClient
                .sql(
                        """
                        UPDATE party_refresh_requests
                        SET attempt_count = attempt_count + 1, next_attempt_at = :nextAttemptAt
                        WHERE party_id = :partyId AND request_version = :version
                        """)
                .param("partyId", partyId)
                .param("version", version)
                .param("nextAttemptAt", time(nextAttemptAt))
                .update();
    }

    private static OffsetDateTime time(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
