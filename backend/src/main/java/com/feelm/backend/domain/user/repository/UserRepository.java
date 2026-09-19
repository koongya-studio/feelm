package com.feelm.backend.domain.user.repository;

import com.feelm.backend.domain.user.entity.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    /** 이메일은 소문자로 정규화해 저장하므로 조회 값도 정규화한 뒤 넘긴다. */
    Optional<User> findByEmail(String email);

    /**
     * 닉네임 중복을 유일 인덱스와 같은 정규화 식으로 판단한다.
     *
     * <p>{@code uix_users_nickname_normalized}가 {@code lower(replace(nickname, ' ', ''))} 식의 함수 기반
     * 인덱스이므로, 같은 식을 쓰지 않으면 인덱스를 타지 못하고 판정 결과도 DB 제약과 달라진다. JPQL로는 이 식을 그대로 표현할 수 없어 Native Query를
     * 사용한다(컨벤션 9장).
     */
    @Query(
            value =
                    "select count(*) from users"
                            + " where lower(replace(nickname, ' ', ''))"
                            + " = lower(replace(:nickname, ' ', ''))",
            nativeQuery = true)
    long countByNormalizedNickname(@Param("nickname") String nickname);

    /**
     * 계정 행을 잠그고 읽는다. 같은 계정을 동시에 바꾸는 요청들이 서로의 결과를 덮어쓰지 않게 한다.
     *
     * <p>호출자는 넷이다.
     *
     * <ul>
     *   <li>비밀번호 재설정(API-011, {@code PasswordResetService.reset}) — 토큰을 소진하고 비밀번호를 바꾸는 동안 같은 계정의 다른
     *       변경이 끼어들지 않게 한다.
     *   <li>비밀번호 변경(API-012, {@code PasswordChangeService.change}) — 같은 사용자의 동시 변경 요청이 서로의 결과를 덮어쓰지
     *       않게 한다.
     *   <li>파티 생성(API-049, {@code PartyService.create}) — 생성과 초대 수락이 동시에 실행돼도 사용자별 활성 파티 상한을 넘지 않게
     *       한다.
     *   <li>구독 OTT 설정(API-027, {@code UserOttService.replaceMyOtts}) — 전체 교체라 동시 요청의 삭제와 삽입이 교차하면
     *       어느 쪽 요청과도 다른 목록이 남으므로, 교체 구간을 계정 단위로 직렬화한다.
     *   <li>회원 탈퇴(API-013, {@code WithdrawnAccountWriter.withdrawAccount}) — 익명화 치환이 프로필 수정·비밀번호
     *       변경과 교차해 되살아나지 않게 한다.
     *   <li>소셜 계정 연결(API-009, {@code OAuthLinkWriter.insert}) — 활성 확인과 삽입 사이에 탈퇴가 끼어들면 탈퇴가 지운 연결이
     *       되살아나 그 소셜 계정으로 재가입할 수 없게 되므로, 탈퇴와 같은 계정 행에서 직렬화한다.
     * </ul>
     *
     * <p>인증의 세 경로는 <b>토큰 → 계정</b> 잠금 순서를 지킨다. 재가입의 삭제 경로도 토큰을 먼저 지우고 계정을 지운다. API-012는 계정 행을 잠그기 전에
     * 재설정 토큰 무효화를 먼저 호출해 이 순서를 지키므로, 재설정과 비밀번호 변경이 동시에 들어와도 교착하지 않는다.
     *
     * <p>탈퇴(API-013)는 이 순서를 거스르지 않기 위해 <b>인증 토큰을 이 트랜잭션에서 지우지 않는다.</b> 계정을 {@code WITHDRAWN}으로 커밋한
     * 뒤 별도 트랜잭션에서 정리한다. 커밋된 뒤의 토큰은 재설정·이메일 인증 어느 경로도 통과시키지 않아 늦게 지워도 무해하다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    /**
     * 비밀번호 대조에 필요한 값만 읽는다. 비밀번호 변경(API-012)의 잠금 없는 사전 검사가 쓴다.
     *
     * <p>Entity로 읽지 않는 이유는 영속성 컨텍스트다. 같은 트랜잭션에서 {@code User}를 Entity로 한 번 읽어 두면 뒤따르는 {@link
     * #findByIdForUpdate}가 행을 다시 읽고 잠그면서도 먼저 읽어 둔 인스턴스를 그대로 돌려준다. 그러면 잠금 뒤의 최종 검사가 사전 검사와 같은 값을 다시
     * 보는 셈이라 "사전 검사 통과 뒤에 다른 트랜잭션이 상태를 바꿨을 가능성"을 잡지 못하고, 이어지는 저장이 그 사이 바뀐 열까지 낡은 값으로 되돌린다 (MR !214
     * 리뷰 2 — 온보딩 완료 직후 비밀번호를 바꾸면 {@code onboarding_completed}가 {@code false}로 돌아갔다).
     */
    @Query(
            "select new com.feelm.backend.domain.user.repository.PasswordCredentials("
                    + "u.status, u.passwordHash) from User u where u.id = :id")
    Optional<PasswordCredentials> findPasswordCredentialsById(@Param("id") Long id);
}
