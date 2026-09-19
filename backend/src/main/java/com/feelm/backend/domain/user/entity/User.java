package com.feelm.backend.domain.user.entity;

import com.feelm.backend.domain.user.WithdrawnAccount;
import com.feelm.backend.global.entity.BaseTimeEntity;
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
        this.status = UserStatus.ACTIVE;
    }

    /**
     * 이메일 가입(API-001)으로 계정을 만든다.
     *
     * <p>이메일 소유는 가입 전에 인증번호(API-078·079)로 이미 확인했으므로 인증 대기 상태를 거치지 않고 곧바로 {@code ACTIVE}다
     * (S15P21E106-603). 소셜 가입과 같은 자리다.
     */
    public static User register(
            String email,
            String passwordHash,
            String nickname,
            LocalDate birthDate,
            Gender gender) {
        return new User(email, passwordHash, nickname, birthDate, gender);
    }

    /**
     * 소셜 가입(API-008)으로 계정을 만든다.
     *
     * <p>비밀번호가 없고, 제공자가 이미 이메일 소유를 확인했으므로 인증 메일 없이 곧바로 {@code ACTIVE}다(API-008 처리 규칙).
     */
    public static User registerSocial(
            String email, String nickname, LocalDate birthDate, Gender gender) {
        return new User(email, null, nickname, birthDate, gender);
    }

    /**
     * 이메일 로그인 수단을 가진 계정인지. 소셜 전용 계정과 탈퇴 계정은 해시가 없다.
     *
     * <p>비밀번호 재설정(API-010)과 변경(API-012)이 대상 계정을 가릴 때 쓴다.
     */
    public boolean hasPassword() {
        return passwordHash != null;
    }

    /**
     * 비밀번호를 새 해시로 바꾼다. 재설정(API-011)과 변경(API-012)이 함께 쓴다.
     *
     * <p>해시로 만드는 책임은 호출자에게 둔다. Entity가 {@code PasswordEncoder}를 알면 영속성 모델이 보안 설정에 의존하게 된다.
     */
    public void changePassword(String encodedPassword) {
        this.passwordHash = encodedPassword;
    }

    /**
     * 프로필 수정(API-015)으로 닉네임을 바꾼다.
     *
     * <p>형식 검증은 Request DTO가, 중복 판정은 Service와 DB 유일 인덱스가 맡는다. Entity는 값을 받은 사실만 반영한다.
     */
    public void changeNickname(String nickname) {
        this.nickname = nickname;
    }

    /** 프로필 수정(API-015)으로 생년월일을 바꾼다. */
    public void changeBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    /** 프로필 수정(API-015)으로 성별을 바꾼다. */
    public void changeGender(Gender gender) {
        this.gender = gender;
    }

    /** 프로필 공개 범위를 바꾼다(API-015). {@code PRIVATE}이면 공개 프로필(API-062)이 즉시 404가 된다. */
    public void changeVisibility(Visibility visibility) {
        this.visibility = visibility;
    }

    /**
     * 초기 취향 제출(API-024)로 온보딩을 완료 처리한다. 건너뛴 제출도 완료다.
     *
     * <p>재실행은 허용하지 않으므로 이 플래그가 두 온보딩 API의 {@code ONBOARDING_ALREADY_COMPLETED} 판정 기준이다. 판정과 전이가
     * 엇갈리지 않도록 호출자가 계정 행을 잠근 뒤 호출한다.
     */
    public void completeOnboarding() {
        this.onboardingCompleted = true;
    }

    /**
     * 탈퇴(API-013)를 반영한다. 계정 행은 남기고 개인정보만 지운다.
     *
     * <p>행을 지우지 않는 이유는 다른 사용자의 데이터가 이 사용자를 가리키고 있기 때문이다(D-9). 대신 이메일·닉네임을 치환해 두 유일 제약을 풀어 같은 값으로 즉시
     * 재가입할 수 있게 하고, 계정 처리 목적이 끝난 비밀번호·생년월일·성별은 비운다. 유예기간과 탈퇴 철회는 두지 않는다.
     *
     * <p>치환 값에 {@code -}를 넣어 살아 있는 닉네임과 절대 겹치지 않게 한다. 닉네임 규칙은 한글·영문·숫자·밑줄만 허용하므로({@link
     * com.feelm.backend.domain.user.NicknamePolicy}) 어떤 사용자도 이 모양을 가질 수 없다. 식별자를 붙여 탈퇴 계정끼리도 겹치지
     * 않는다. 이메일은 예약 TLD {@code .invalid}(RFC 2606)를 써서 실재하는 주소가 되지 않게 한다.
     *
     * <p>공개 범위는 {@code PRIVATE}으로 되돌린다. 공개로 두었던 계정이 탈퇴 뒤에도 공개 프로필(API-062)에 남아서는 안 된다.
     *
     * <p>치환은 계정 행 전체를 익명정보로 만들지 않는다. 내부 식별자와 {@code public_id}, 다른 사용자 데이터와의 연결이 그대로 남는다(D-9).
     */
    public void withdraw(Instant now) {
        this.email = WithdrawnAccount.email(id);
        this.nickname = WithdrawnAccount.nickname(id);
        this.passwordHash = null;
        this.birthDate = null;
        this.gender = null;
        this.visibility = Visibility.PRIVATE;
        this.status = UserStatus.WITHDRAWN;
        this.withdrawnAt = now;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public boolean isWithdrawn() {
        return status == UserStatus.WITHDRAWN;
    }
}
