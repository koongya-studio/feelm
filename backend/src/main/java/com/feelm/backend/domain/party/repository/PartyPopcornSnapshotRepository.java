package com.feelm.backend.domain.party.repository;

import com.feelm.backend.domain.party.dto.response.PartyPopcornResponse.FlavorBucket;
import com.feelm.backend.domain.party.dto.response.PartyResponse;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
@RequiredArgsConstructor
public class PartyPopcornSnapshotRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper mapper;

    public void insert(
            long partyId,
            long setId,
            OffsetDateTime exposedAt,
            List<SavedMember> members,
            String mode) {
        jdbc.update(
                "INSERT INTO party_popcorn_snapshots(party_id,set_id,exposed_at,members,recommendation_mode) VALUES (?,?,?,CAST(? AS jsonb),?)",
                partyId,
                setId,
                exposedAt,
                mapper.writeValueAsString(members),
                mode);
    }

    public List<StoredSnapshot> latest(long partyId) {
        return query(
                "SELECT * FROM party_popcorn_snapshots WHERE party_id=? ORDER BY exposed_at DESC,id DESC LIMIT 1",
                partyId);
    }

    public List<StoredSnapshot> forSets(long partyId, List<Long> setIds) {
        if (setIds.isEmpty()) return List.of();
        return namedJdbc.query(
                "SELECT * FROM party_popcorn_snapshots WHERE party_id=:partyId AND set_id IN (:setIds) ORDER BY exposed_at,id",
                Map.of("partyId", partyId, "setIds", setIds),
                (rs, row) -> parse(rs));
    }

    public List<StoredSnapshot> current(long partyId, long setId, OffsetDateTime exposedAt) {
        return query(
                "SELECT * FROM party_popcorn_snapshots WHERE party_id=? AND set_id=? AND exposed_at=?",
                partyId,
                setId,
                exposedAt);
    }

    private List<StoredSnapshot> query(String sql, Object... args) {
        return jdbc.query(sql, (rs, row) -> parse(rs), args);
    }

    private StoredSnapshot parse(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StoredSnapshot(
                rs.getLong("set_id"),
                rs.getObject("exposed_at", OffsetDateTime.class),
                rs.getString("recommendation_mode"),
                List.of(mapper.readValue(rs.getString("members"), SavedMember[].class)));
    }

    public Map<Long, StoredUser> users(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long, StoredUser> result = new HashMap<>();
        namedJdbc.query(
                "SELECT id,nickname,avatar_storage_key,status FROM users WHERE id IN (:ids)",
                Map.of("ids", ids),
                rs -> {
                    boolean withdrawn = "WITHDRAWN".equals(rs.getString("status"));
                    result.put(
                            rs.getLong("id"),
                            new StoredUser(
                                    withdrawn
                                            ? PartyResponse.UserSummary.WITHDRAWN_NICKNAME
                                            : rs.getString("nickname"),
                                    withdrawn ? null : rs.getString("avatar_storage_key")));
                });
        return result;
    }

    public record StoredUser(String nickname, String avatarStorageKey) {}

    public record SavedMember(long userId, long totalCount, List<FlavorBucket> flavors) {}

    public record StoredSnapshot(
            long setId, OffsetDateTime exposedAt, String mode, List<SavedMember> members) {}
}
