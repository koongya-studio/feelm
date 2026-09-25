package com.feelm.backend.domain.social.repository;

import com.feelm.backend.domain.user.entity.Visibility;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * API-062가 읽는 대상 계정과 공개 한줄평 수를 PostgreSQL에서 읽는 projection repository.
 *
 * <p>계정 행을 사용자 도메인의 Repository 대신 여기서 직접 읽는다. 공개 프로필이 필요한 값은 {@code id}·{@code nickname}·{@code
 * visibility} 셋뿐이고 쓰기가 없어, 다른 도메인의 Entity 로딩 경로를 늘리는 대신 소유 도메인이 정한 열만 projection으로 가져온다. 같은 패키지의
 * {@link PublicReviewQueryRepository}가 접근 판정에 {@code users}를 읽는 것과 같은 방식이다.
 *
 * <p><b>차단 판정은 여기서 하지 않는다.</b> 양방향 의미를 SQL로 복제하지 않고 사용자 도메인의 {@code UserBlockFilter}를 호출한다(FR-39,
 * 컨벤션 11장).
 */
@Repository
@RequiredArgsConstructor
public class PublicProfileQueryRepository {

    private static final String TARGET_SQL =
            """
            SELECT id, nickname, avatar_storage_key, visibility,
                   frame_visibility, popcorn_visibility, review_visibility
            FROM users
            WHERE public_id = :publicId AND status = 'ACTIVE'
            """;

    /**
     * 공개 한줄평 수. 작성자 기준 집계라 {@code uk_ratings_user_id_movie_id}의 선두 컬럼을 탄다.
     *
     * <p>공백만 있는 한줄평을 비어 있는 것으로 보는 규칙을 목록 API(API-032)와 공유해야 하므로 같은 술어를 재사용한다. 규칙이 갈라지면 목록에 보이지 않는
     * 한줄평이 수에는 잡힌다.
     */
    private static final String PUBLIC_REVIEW_COUNT_SQL =
            """
            SELECT COUNT(*)
            FROM ratings r
            WHERE r.user_id = :userId
              AND r.visibility = 'PUBLIC'
              AND r.comment IS NOT NULL
              AND %s
            """
                    .formatted(PublicReviewQueryRepository.NON_BLANK_COMMENT_PREDICATE);

    private final JdbcClient jdbc;

    /** 활성 계정만 돌려준다. 탈퇴·미인증 계정은 "없음"과 구분하지 않는다. */
    public Optional<ProfileTarget> findActiveTargetByPublicId(UUID publicId) {
        return jdbc.sql(TARGET_SQL)
                .param("publicId", publicId)
                .query(
                        (row, rowNumber) ->
                                new ProfileTarget(
                                        row.getLong("id"),
                                        row.getString("nickname"),
                                        row.getString("avatar_storage_key"),
                                        Visibility.valueOf(row.getString("visibility")),
                                        Visibility.valueOf(row.getString("frame_visibility")),
                                        Visibility.valueOf(row.getString("popcorn_visibility")),
                                        Visibility.valueOf(row.getString("review_visibility"))))
                .optional();
    }

    public long countPublicReviews(long userId) {
        return jdbc.sql(PUBLIC_REVIEW_COUNT_SQL).param("userId", userId).query(Long.class).single();
    }

    /** 대상 계정. 세 항목 공개 범위는 S15P21E106-856에서 더했다. */
    public record ProfileTarget(
            long userId,
            String nickname,
            String avatarStorageKey,
            Visibility visibility,
            Visibility frameVisibility,
            Visibility popcornVisibility,
            Visibility reviewVisibility) {}
}
