package com.feelm.backend.domain.recommendation.repository;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RecommendationReasonRepository {

    private final JdbcTemplate jdbcTemplate;

    public List<UnresolvedItem> findUnresolved(long setId) {
        return jdbcTemplate.query(
                """
                SELECT id, type, cast(reason AS text) AS reason
                FROM recommendation_items
                WHERE set_id=? AND exposed_at IS NULL
                  AND (reason->>'summary' IS NULL OR reason->>'_reasonPending' = 'true')
                ORDER BY id
                """,
                (row, ignored) ->
                        new UnresolvedItem(
                                row.getLong("id"), row.getString("type"), row.getString("reason")),
                setId);
    }

    public Optional<String> findMovieTitle(long movieId) {
        return jdbcTemplate
                .queryForList("SELECT title FROM movies WHERE id=?", String.class, movieId)
                .stream()
                .filter(title -> title != null && !title.isBlank())
                .findFirst();
    }

    public Optional<String> findGenreName(String genreCode) {
        return jdbcTemplate
                .queryForList("SELECT name FROM genres WHERE code=?", String.class, genreCode)
                .stream()
                .filter(name -> name != null && !name.isBlank())
                .findFirst();
    }

    /** 이미 노출되었거나 다른 요청이 확정한 문구는 덮어쓰지 않는다. */
    public boolean completeIfUnexposed(long itemId, String summary, String factorsJson) {
        return completeIfUnexposed(itemId, summary, factorsJson, "[]");
    }

    /** 이미 노출되었거나 다른 요청이 확정한 문구는 덮어쓰지 않는다. */
    public boolean completeIfUnexposed(
            long itemId, String summary, String factorsJson, String usedEvidenceIdsJson) {
        return jdbcTemplate.update(
                        """
                        UPDATE recommendation_items
                        SET reason = (reason - '_reasonPending')
                                     || jsonb_build_object('summary', cast(? AS text),
                                                                  'factors', cast(? AS jsonb),
                                                                  'usedEvidenceIds', cast(? AS jsonb))
                        WHERE id=? AND exposed_at IS NULL
                          AND (reason->>'summary' IS NULL OR reason->>'_reasonPending' = 'true')
                        """,
                        summary,
                        factorsJson,
                        usedEvidenceIdsJson,
                        itemId)
                == 1;
    }

    public record UnresolvedItem(long id, String type, String reason) {}
}
