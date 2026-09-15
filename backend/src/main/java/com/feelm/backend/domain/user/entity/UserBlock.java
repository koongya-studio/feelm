package com.feelm.backend.domain.user.entity;

import com.feelm.backend.global.entity.BaseTimeEntity;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자 차단 관계. {@code user_blocks} 테이블에 매핑한다.
 *
 * <p>행은 차단한 쪽에서 차단당한 쪽으로 향하는 단방향으로만 저장하고, 노출 차단 판정은 조회 시 양방향 OR 조건으로 본다(ERD "차단·공개 범위"). 따라서 A가 B를
 * 차단한 뒤 B가 A를 차단하면 서로 다른 두 행이 남는다.
 */
@Entity
@Table(
        name = "user_blocks",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_user_blocks_blocker_id_blocked_id",
                        columnNames = {"blocker_id", "blocked_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserBlock extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blocker_id", nullable = false)
    private User blocker;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blocked_id", nullable = false)
    private User blocked;

    private UserBlock(User blocker, User blocked) {
        this.blocker = blocker;
        this.blocked = blocked;
    }

    /**
     * 차단 관계를 만든다(API-020).
     *
     * @throws BusinessException 자기 자신을 차단하면 {@code CANNOT_BLOCK_SELF}. DB의 {@code
     *     ck_user_blocks_not_self} 제약과 같은 규칙이며, 제약 위반을 예외 메시지로 노출하지 않기 위해 여기서 먼저 막는다
     */
    public static UserBlock of(User blocker, User blocked) {
        if (blocker.getId().equals(blocked.getId())) {
            throw new BusinessException(ErrorCode.CANNOT_BLOCK_SELF);
        }
        return new UserBlock(blocker, blocked);
    }
}
