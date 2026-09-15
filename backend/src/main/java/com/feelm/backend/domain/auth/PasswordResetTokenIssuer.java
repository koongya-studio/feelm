package com.feelm.backend.domain.auth;

import com.feelm.backend.domain.auth.entity.PasswordResetToken;
import com.feelm.backend.domain.auth.repository.PasswordResetTokenRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 비밀번호 재설정 토큰의 발급과 무효화를 담당한다. 골격은 {@link OneTimeTokenIssuer}에 있고 수명만 다르다(API-010의 30분).
 *
 * <p>가입 인증 토큰과 달리 <b>새 토큰 발급 없이 무효화만 하는 경로</b>(비밀번호 변경, API-012)가 있어 사용자 단위 직렬화를 함께 쓴다.
 */
@Component
public class PasswordResetTokenIssuer extends OneTimeTokenIssuer<PasswordResetToken> {

    /** 재설정 토큰 유효기간(API-010). 메일 본문의 "30분" 문구와 짝이다({@code MailKind}). */
    private static final Duration TOKEN_TTL = Duration.ofMinutes(30);

    /**
     * 사용자 단위 무효화 락의 스트라이프 수(283 리뷰 발견 1). 애플리케이션 인스턴스가 하나일 때만 정확하다 — 수평 확장 시에는 인스턴스마다 별도 JVM 락을
     * 가지므로 이 보호가 깨진다. 배포 형태가 다중 인스턴스로 바뀌면 DB 기반 잠금으로 다시 바꿔야 한다.
     */
    private static final int LOCK_STRIPES = 64;

    private final ReentrantLock[] userLocks =
            Stream.generate(ReentrantLock::new).limit(LOCK_STRIPES).toArray(ReentrantLock[]::new);

    public PasswordResetTokenIssuer(PasswordResetTokenRepository tokenRepository) {
        super(tokenRepository, TOKEN_TTL);
    }

    /**
     * 새 토큰 발급 없이 해당 사용자의 미사용 재설정 토큰만 전부 무효화한다.
     *
     * <p>비밀번호 변경(API-012)에서 쓴다. 재설정 메일이 유출된 상태에서 비밀번호를 다른 경로로 바꿔도 남은 링크가 최대 30분 더 유효해서는 안 되기 때문이다.
     * 호출자의 트랜잭션 안에서 동작한다.
     */
    public void invalidateAllUnused(Long userId) {
        invalidateUnused(userId, Instant.now());
    }

    /**
     * 무효화 조회 전에 사용자 단위 JVM 락으로 {@code issue()}·{@code invalidateAllUnused()} 동시 호출을 직렬화한다(283 리뷰 발견
     * 1).
     *
     * <p>{@code findAllByUserIdAndUsedAtIsNull}의 행 잠금만으로는 발급 중(아직 미커밋)인 새 토큰까지 막지 못한다. Postgres의
     * {@code SELECT ... FOR UPDATE}는 이미 존재하는 행만 잠그고, 커밋 전 INSERT는 다른 트랜잭션에 보이지도 잠기지도 않기 때문이다.
     *
     * <p>이 락은 이 메서드가 반환할 때가 아니라 호출자의 트랜잭션이 끝날 때(커밋·롤백) 풀린다 — 그래야 무효화 이후에 이어지는 INSERT({@code
     * issue()})나 계정 변경({@code invalidateAllUnused()} 호출자)까지 보호 범위에 들어간다. 상위 클래스의 불변식(모든 공개 메서드가
     * 호출자의 트랜잭션 안에서 동작한다)이 전제이므로 트랜잭션 동기화가 항상 등록된다.
     */
    @Override
    protected void beforeInvalidate(Long userId) {
        ReentrantLock lock = userLocks[Math.floorMod(userId.hashCode(), LOCK_STRIPES)];
        lock.lock();
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        lock.unlock();
                    }
                });
    }

    @Override
    protected PasswordResetToken newToken(Long userId, String tokenHash, Instant expiresAt) {
        return PasswordResetToken.issue(userId, tokenHash, expiresAt);
    }
}
