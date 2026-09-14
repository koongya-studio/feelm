package com.feelm.backend.domain.onboarding.entity;

import com.feelm.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 온보딩에서 수집한 초기 취향. {@code onboarding_preferences} 테이블에 매핑한다.
 *
 * <p>사용자와 영화 모두 연관관계 대신 식별자만 보관한다. 이 유스케이스는 계정 엔티티의 온보딩 완료 플래그만 바꾸고 영화는 적재 경로가 소유하는 기준정보이기 때문이다
 * (컨벤션 7장, {@code UserOtt}와 같은 기준).
 */
@Entity
@Table(
        name = "onboarding_preferences",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_onboarding_preferences_user_id_movie_id",
                        columnNames = {"user_id", "movie_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OnboardingPreference extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "movie_id", nullable = false)
    private Long movieId;

    @Enumerated(EnumType.STRING)
    @Column(name = "preference", nullable = false, length = 10)
    private PreferenceChoice preference;

    private OnboardingPreference(Long userId, Long movieId, PreferenceChoice preference) {
        this.userId = userId;
        this.movieId = movieId;
        this.preference = preference;
    }

    public static OnboardingPreference of(long userId, long movieId, PreferenceChoice preference) {
        return new OnboardingPreference(userId, movieId, preference);
    }
}
