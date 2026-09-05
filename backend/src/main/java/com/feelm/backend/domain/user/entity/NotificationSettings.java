package com.feelm.backend.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 알림 수신 설정. 사용자당 1행이므로 {@code user_id}가 기본키이자 외래키다.
 *
 * <p>가입 트랜잭션에서 기본행을 함께 만든다(플랜 결정 10). 조회·변경(API-017·018)은 별도 업무(S15P21E106-152)가 담당하므로 여기서는 생성만
 * 제공한다.
 *
 * <p>기본값을 DB DEFAULT가 아니라 필드 초기값으로 두는 이유는 애플리케이션이 만드는 행의 값을 한 곳에서 읽히게 하고, DDL을 JPA로 만드는 테스트 환경에서도
 * 같은 기본값이 적용되게 하기 위해서다. 마이그레이션의 DEFAULT는 SQL로 직접 삽입하는 경로를 위한 이중 안전장치로 남는다.
 */
@Entity
@Table(name = "notification_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationSettings {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "rating_enabled", nullable = false)
    private boolean ratingEnabled = true;

    @Column(name = "recommendation_enabled", nullable = false)
    private boolean recommendationEnabled = true;

    @Column(name = "marketing_enabled", nullable = false)
    private boolean marketingEnabled = false;

    private NotificationSettings(Long userId) {
        this.userId = userId;
    }

    /** API-017 처리 규칙의 기본값(평가·추천 수신, 마케팅 미수신)으로 행을 만든다. */
    public static NotificationSettings createDefault(Long userId) {
        return new NotificationSettings(userId);
    }
}
