package com.feelm.backend.domain.feedback.service;

import com.feelm.backend.domain.feedback.dto.FeedbackRequest;
import com.feelm.backend.domain.feedback.dto.FeedbackResponse;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FeedbackService {

    private final NamedParameterJdbcTemplate jdbc;

    @Transactional
    public FeedbackResponse submit(long userId, FeedbackRequest request) {
        // 탈퇴도 users 행을 먼저 잠근다. 같은 잠금으로 탈퇴 뒤 재삽입과 교차 실행을 막는다.
        String status =
                jdbc.query(
                        "SELECT status FROM users WHERE id = :userId FOR UPDATE",
                        Map.of("userId", userId),
                        result -> result.next() ? result.getString(1) : null);
        if (!"ACTIVE".equals(status)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        String comment = request.comment() == null ? null : request.comment().trim();
        String screen = request.screenPath();
        if ((request.satisfaction() == null && (comment == null || comment.isEmpty()))
                || !screen.matches("^/app/[A-Za-z0-9_/?=&.%-]*$")
                || screen.contains("//")
                || screen.contains("..")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                """
                INSERT INTO user_feedback
                    (user_id, category, satisfaction, comment, screen_path, movie_id, recommendation_type)
                VALUES (:userId, :category, :satisfaction, :comment, :screenPath, :movieId, :recommendationType)
                """,
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("category", request.category().name())
                        .addValue(
                                "satisfaction",
                                request.satisfaction() == null
                                        ? null
                                        : request.satisfaction().name())
                        .addValue("comment", comment)
                        .addValue("screenPath", screen)
                        .addValue("movieId", request.movieId())
                        .addValue("recommendationType", request.recommendationType()),
                key,
                new String[] {"id"});
        long id = key.getKey().longValue();
        OffsetDateTime createdAt =
                jdbc.queryForObject(
                        "SELECT created_at FROM user_feedback WHERE id = :id",
                        Map.of("id", id),
                        (rs, row) -> rs.getObject("created_at", OffsetDateTime.class));
        return new FeedbackResponse(id, createdAt);
    }
}
