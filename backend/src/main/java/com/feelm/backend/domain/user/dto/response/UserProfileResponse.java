package com.feelm.backend.domain.user.dto.response;

import com.feelm.backend.domain.user.entity.Gender;
import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.entity.Visibility;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * API-014 내 프로필 조회 응답. 프로필 수정(API-015)도 같은 본문을 돌려준다.
 *
 * <p>여러 출처(계정, 소셜 연결 목록, 활동 집계)를 조립하므로 Entity 하나를 받는 {@code from} 대신 조립한 값을 함께 받는다.
 */
public record UserProfileResponse(
        long userId,
        String email,
        String nickname,
        String avatarUrl,
        LocalDate birthDate,
        Gender gender,
        Visibility visibility,
        Visibility frameVisibility,
        Visibility popcornVisibility,
        Visibility reviewVisibility,
        boolean hasPassword,
        List<String> linkedProviders,
        boolean onboardingCompleted,
        Stats stats,
        Instant createdAt) {

    public static UserProfileResponse of(
            User user, String avatarUrl, List<String> linkedProviders, Stats stats) {
        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                avatarUrl,
                user.getBirthDate(),
                user.getGender(),
                user.getVisibility(),
                user.getFrameVisibility(),
                user.getPopcornVisibility(),
                user.getReviewVisibility(),
                user.hasPassword(),
                linkedProviders,
                user.isOnboardingCompleted(),
                stats,
                user.getCreatedAt());
    }

    /**
     * 프레임·팝콘·평가 수(API-014 처리 규칙).
     *
     * <p>프레임 수와 평가 수는 1:1이고, 팝콘 수는 평가를 영화의 대표 맛별로 집계한 값이라 대표 맛이 없는 영화의 평가만큼 작을 수 있다.
     */
    public record Stats(long frameCount, long popcornCount, long ratingCount) {}
}
