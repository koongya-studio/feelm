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
 * <p>가입 트랜잭션에서 기본행을 함께 만든다(플랜 결정 10). 조회와 변경(API-017·018)은 S15P21E106-207에서 구현했고, 변경 경로는 전체 항목
 * 교체뿐이다. 파티 초대 항목은 S15P21E106-857, 리포트 발급 항목은 S15P21E106-858에서 더했다.
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

    /** 끄면 파티 초대 알림 행을 만들지 않는다(S15P21E106-857, {@code NotificationRepository}). */
    @Column(name = "party_invite_enabled", nullable = false)
    private boolean partyInviteEnabled = true;

    /** 끄면 리포트 발급 알림 행을 만들지 않는다(S15P21E106-858, {@code NotificationRepository}). */
    @Column(name = "report_issued_enabled", nullable = false)
    private boolean reportIssuedEnabled = true;

    @Column(name = "marketing_enabled", nullable = false)
    private boolean marketingEnabled = false;

    private NotificationSettings(Long userId) {
        this.userId = userId;
    }

    /** API-017 처리 규칙의 기본값(평가·추천·파티 초대·리포트 발급 수신, 마케팅 미수신)으로 행을 만든다. */
    public static NotificationSettings createDefault(Long userId) {
        return new NotificationSettings(userId);
    }

    /**
     * 모든 항목을 한 번에 바꾼다(API-018).
     *
     * <p>변경 경로가 전체 교체뿐이라 항목별 변경 메서드를 두지 않는다. 모든 값을 함께 덮으므로 두 요청이 겹쳐도 어느 한쪽 요청과 같은 상태만 남고 항목이 섞이지
     * 않는다.
     */
    public void replace(
            boolean ratingEnabled,
            boolean recommendationEnabled,
            boolean partyInviteEnabled,
            boolean reportIssuedEnabled,
            boolean marketingEnabled) {
        this.ratingEnabled = ratingEnabled;
        this.recommendationEnabled = recommendationEnabled;
        this.partyInviteEnabled = partyInviteEnabled;
        this.reportIssuedEnabled = reportIssuedEnabled;
        this.marketingEnabled = marketingEnabled;
    }
}
