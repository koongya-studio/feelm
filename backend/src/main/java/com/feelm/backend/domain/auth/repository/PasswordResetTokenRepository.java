package com.feelm.backend.domain.auth.repository;

import com.feelm.backend.domain.auth.entity.PasswordResetToken;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    /**
     * 토큰 원문이 아닌 해시로 조회한다.
     *
     * <p>행을 잠그고 읽는다. 같은 토큰으로 재설정 요청이 동시에 들어오면 뒤의 요청은 앞의 커밋을 기다린 뒤 소진된 상태를 보게 되어 1회용이 지켜진다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /** 아직 소진되지 않은 토큰. 재요청(API-010)이 이전 토큰을 무효화할 때 사용한다. */
    List<PasswordResetToken> findAllByUserIdAndUsedAtIsNull(Long userId);
}
