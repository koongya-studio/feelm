package com.feelm.backend.domain.auth;

import com.feelm.backend.domain.auth.entity.PasswordResetToken;
import com.feelm.backend.domain.auth.repository.PasswordResetTokenRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 비밀번호 재설정 토큰의 발급과 무효화를 담당한다.
 *
 * <p>토큰은 의미가 없는 난수 32바이트를 Base64URL(패딩 없음)로 인코딩한 문자열이고, DB에는 SHA-256 해시만 저장한다. 가입 인증 토큰({@link
 * VerificationTokenIssuer})과 같은 방식이며 수명만 다르다(API-010의 30분).
 *
 * <p>호출자의 트랜잭션 안에서 동작한다. 토큰 저장과 이전 토큰 무효화가 한 트랜잭션이어야 하기 때문이다.
 */
@Component
@RequiredArgsConstructor
public class PasswordResetTokenIssuer {

    /** 재설정 토큰 유효기간(API-010). */
    private static final Duration TOKEN_TTL = Duration.ofMinutes(30);

    private static final int TOKEN_BYTES = 32;

    /**
     * 사용자 단위 무효화 락의 스트라이프 수(283 리뷰 발견 1). 애플리케이션 인스턴스가 하나일 때만 정확하다 — 수평 확장 시에는 인스턴스마다 별도 JVM 락을
     * 가지므로 이 보호가 깨진다. 배포 형태가 다중 인스턴스로 바뀌면 DB 기반 잠금으로 다시 바꿔야 한다.
     */
    private static final int LOCK_STRIPES = 64;

    private final PasswordResetTokenRepository tokenRepository;
    private final SecureRandom random = new SecureRandom();
    private final ReentrantLock[] userLocks =
            Stream.generate(ReentrantLock::new).limit(LOCK_STRIPES).toArray(ReentrantLock[]::new);

    /**
     * 기존 미사용 토큰을 무효화하고 새 토큰을 발급한다.
     *
     * @return 메일 링크에 실을 토큰 원문
     */
    public String issue(Long userId) {
        Instant now = Instant.now();
        invalidateUnused(userId, now);

        String rawToken = generateRawToken();
        tokenRepository.save(
                PasswordResetToken.issue(userId, hashOf(rawToken), now.plus(TOKEN_TTL)));
        return rawToken;
    }

    /** 저장된 해시와 비교할 수 있도록 토큰 원문을 같은 방식으로 변환한다. */
    public String hashOf(String rawToken) {
        return Sha256Hasher.hex(rawToken);
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
     * 다시 요청하면 이전 링크는 즉시 쓸 수 없어야 한다. 유효한 링크가 여러 개 떠 있으면 1회용의 의미가 약해진다.
     *
     * <p>{@code findAllByUserIdAndUsedAtIsNull}의 행 잠금만으로는 발급 중(아직 미커밋)인 새 토큰까지 막지 못한다. Postgres의
     * {@code SELECT ... FOR UPDATE}는 이미 존재하는 행만 잠그고, 커밋 전 INSERT는 다른 트랜잭션에 보이지도 잠기지도 않기 때문이다. 그래서
     * 조회 전에 사용자 단위 JVM 락으로 {@code issue()}·{@code invalidateAllUnused()} 동시 호출 자체를 직렬화한다(283 리뷰 발견
     * 1). 이 락은 이 메서드가 반환할 때가 아니라 호출자의 트랜잭션이 끝날 때(커밋·롤백) 풀린다 — 그래야 무효화 이후에 이어지는 INSERT(issue())나 계정
     * 변경(invalidateAllUnused() 호출자)까지 보호 범위에 들어간다. 이 클래스의 모든 공개 메서드가 호출자의 트랜잭션 안에서 동작한다는 불변식(클래스
     * Javadoc)이 전제이므로 트랜잭션 동기화가 항상 등록된다.
     */
    private void invalidateUnused(Long userId, Instant now) {
        ReentrantLock lock = userLocks[Math.floorMod(userId.hashCode(), LOCK_STRIPES)];
        lock.lock();
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        lock.unlock();
                    }
                });
        tokenRepository
                .findAllByUserIdAndUsedAtIsNull(userId)
                .forEach(token -> token.markUsed(now));
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
