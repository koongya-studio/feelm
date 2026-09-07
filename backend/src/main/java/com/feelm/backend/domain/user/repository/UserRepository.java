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
     * 계정 행을 잠그고 읽는다. 이메일 인증(API-002)이 상태를 바꾸는 동안 재가입(API-001)의 미인증 계정 삭제가 끼어들지 않게 한다.
     *
     * <p>잠금 순서는 토큰 → 계정이다. 재가입의 삭제 경로도 토큰을 먼저 지우고 계정을 지우므로 두 경로가 교착하지 않는다.
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
