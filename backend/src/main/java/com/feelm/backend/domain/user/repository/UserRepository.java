package com.feelm.backend.domain.user.repository;

import com.feelm.backend.domain.user.entity.User;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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
     *   <li>이메일 인증(API-002, {@code EmailVerificationService.verify}) — 상태를 활성으로 바꾸는 동안
     *       재가입(API-001)의 미인증 계정 삭제가 끼어들지 않게 한다.
     *   <li>비밀번호 재설정(API-011, {@code PasswordResetService.reset}) — 토큰을 소진하고 비밀번호를 바꾸는 동안 같은 계정의 다른
     *       변경이 끼어들지 않게 한다.
     *   <li>비밀번호 변경(API-012, {@code PasswordChangeService.change}) — 같은 사용자의 동시 변경 요청이 서로의 결과를 덮어쓰지
     *       않게 한다.
     *   <li>파티 생성(API-049, {@code PartyService.create}) — 생성과 초대 수락이 동시에 실행돼도 사용자별 활성 파티 상한을 넘지 않게
     *       한다.
     *   <li>구독 OTT 설정(API-027, {@code UserOttService.replaceMyOtts}) — 전체 교체라 동시 요청의 삭제와 삽입이 교차하면
     *       어느 쪽 요청과도 다른 목록이 남으므로, 교체 구간을 계정 단위로 직렬화한다.
     * </ul>
     *
     * <p>인증의 세 경로는 <b>토큰 → 계정</b> 잠금 순서를 지킨다. 재가입의 삭제 경로도 토큰을 먼저 지우고 계정을 지운다. API-012는 계정 행을 잠그기 전에
     * 재설정 토큰 무효화를 먼저 호출해 이 순서를 지키므로, 재설정과 비밀번호 변경이 동시에 들어와도 교착하지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    /**
     * 보존 기간이 지난 미인증 계정을 지운다. 상태와 생성 시각을 삭제문의 조건에 넣어, 조회 뒤 삭제 전에 다른 요청이 인증을 마쳤으면 지우지 않는다.
     *
     * @return 지운 행 수. {@code 0}이면 그 사이에 계정이 활성화된 것이므로 호출자가 재가입을 거절한다
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "delete from User u where u.id = :id"
                    + " and u.status = com.feelm.backend.domain.user.entity.UserStatus.PENDING_VERIFICATION"
                    + " and u.createdAt <= :cutoff")
    int deleteIfStalePendingVerification(@Param("id") Long id, @Param("cutoff") Instant cutoff);
}
