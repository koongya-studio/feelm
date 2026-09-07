package com.feelm.backend.domain.auth.repository;

import com.feelm.backend.domain.auth.entity.EmailVerificationToken;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface EmailVerificationTokenRepository
        extends JpaRepository<EmailVerificationToken, Long> {

    /**
     * 토큰 원문이 아닌 해시로 조회한다(플랜 결정 8).
     *
     * <p>행을 잠그고 읽는다. 같은 토큰으로 인증 요청이 동시에 들어오면 뒤의 요청은 앞의 커밋을 기다린 뒤 소진된 상태를 보게 되어 1회용이 지켜진다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    /** 아직 소진되지 않은 토큰. 재발송(API-003)이 이전 토큰을 무효화할 때 사용한다. */
    List<EmailVerificationToken> findAllByUserIdAndUsedAtIsNull(Long userId);

    /** 계정 행을 삭제하는 경로에서 함께 정리한다. FK CASCADE에만 의존하지 않는 이유는 알림 설정 쪽과 같다. */
    void deleteByUserId(Long userId);
}
