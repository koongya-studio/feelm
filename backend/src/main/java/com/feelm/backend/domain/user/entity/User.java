package com.feelm.backend.domain.user.entity;

import com.feelm.backend.global.entity.BaseTimeEntity;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 서비스 계정. {@code users} 테이블에 매핑한다.
 *
 * <p>탈퇴는 물리 삭제가 아니라 상태 전이와 익명화 치환이므로(D-9) {@code passwordHash}, {@code birthDate}, {@code gender}는
 * NULL을 허용한다. 가입 경로(API-001)에서는 세 값이 모두 필수이며, 그 검증은 Request DTO가 담당한다.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 공개 URL 노출용 식별자(ADR-0008). 값은 DB DEFAULT가 생성하므로 애플리케이션은 읽기만 한다. {@code nullable = false}를 선언하지
     * 않는 이유는 DDL이 Flyway가 아닌 JPA로 만들어지는 테스트 환경에서 DEFAULT가 없어 삽입이 실패하기 때문이다.
     */
    @Column(name = "public_id", insertable = false, updatable = false)
    private UUID publicId;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "nickname", nullable = false, length = 20)
    private String nickname;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", length = 10)
    private Gender gender;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 10)
    private Visibility visibility = Visibility.PRIVATE;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private UserStatus status;

    @Column(name = "onboarding_completed", nullable = false)
    private boolean onboardingCompleted = false;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    private User(
            String email,
            String passwordHash,
            String nickname,
            LocalDate birthDate,
            Gender gender) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.birthDate = birthDate;
        this.gender = gender;
        this.status = UserStatus.PENDING_VERIFICATION;
    }

    /** 이메일 가입(API-001)으로 계정을 만든다. 이메일 인증을 마칠 때까지 {@code PENDING_VERIFICATION}이다. */
    public static User register(
            String email,
            String passwordHash,
            String nickname,
            LocalDate birthDate,
            Gender gender) {
        return new User(email, passwordHash, nickname, birthDate, gender);
    }

    /**
     * 이메일 인증(API-002)을 반영해 계정을 활성화한다.
     *
     * @throws BusinessException 이미 활성화된 계정은 {@code ALREADY_VERIFIED}, 탈퇴한 계정은 {@code
     *     VERIFICATION_TOKEN_INVALID}
     */
    public void activate() {
        if (status == UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ALREADY_VERIFIED);
        }
        if (status != UserStatus.PENDING_VERIFICATION) {
            throw new BusinessException(ErrorCode.VERIFICATION_TOKEN_INVALID);
        }
        status = UserStatus.ACTIVE;
    }

    public boolean isPendingVerification() {
        return status == UserStatus.PENDING_VERIFICATION;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public boolean isWithdrawn() {
        return status == UserStatus.WITHDRAWN;
    }
}
