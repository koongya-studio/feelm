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

    /**
     * 아직 소진되지 않은 토큰. 재요청(API-010)이 이전 토큰을 무효화할 때, 비밀번호 변경(API-012)이 남은 토큰을 무효화할 때 사용한다.
     *
     * <p>행을 잠그고 읽는다. {@link #findByTokenHash}와 같은 이유로, 같은 사용자에 대한 재설정 요청과 비밀번호 변경이 동시에 들어와도 서로의 무효화
     * 처리가 덮어써지지 않게 한다.
     *
     * <p><b>이 잠금만으로는 부족하다.</b> {@code SELECT ... FOR UPDATE}는 이미 존재하는 행만 잠그고, 이 조회 시점에 아직 커밋되지 않은
     * 다른 트랜잭션의 INSERT(재요청으로 발급 중인 새 토큰)는 보이지도 잠기지도 않는다. 그래서 {@code
     * PasswordResetTokenIssuer.invalidateUnused()}가 이 메서드를 호출하기 전에 사용자 단위 애플리케이션 락으로 {@code
     * issue()}·{@code invalidateAllUnused()} 동시 호출 자체를 직렬화한다(283 리뷰 발견 1).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<PasswordResetToken> findAllByUserIdAndUsedAtIsNull(Long userId);
}
