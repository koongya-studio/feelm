package com.feelm.backend.domain.ott.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자가 구독 중으로 설정한 OTT. {@code user_otts} 테이블에 매핑한다.
 *
 * <p>설정 경로가 전체 교체(API-027)뿐이라 마이그레이션이 대리키 없이 복합 기본키를 쓴다. 사용자와 OTT 모두 연관관계 대신 식별자만 보관한다. {@code
 * otts}는 외부 적재 경로가 함께 쓰는 테이블이고(컨벤션 7장), 사용자 쪽도 이 유스케이스가 계정 엔티티의 상태를 바꾸지 않기 때문이다.
 */
@Entity
@Table(name = "user_otts")
@IdClass(UserOttId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserOtt {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(name = "ott_id", nullable = false)
    private Long ottId;

    private UserOtt(Long userId, Long ottId) {
        this.userId = userId;
        this.ottId = ottId;
    }

    public static UserOtt of(long userId, long ottId) {
        return new UserOtt(userId, ottId);
    }
}
