package com.feelm.backend.domain.auth;

import com.feelm.backend.domain.auth.entity.OneTimeTokenEntity;
import com.feelm.backend.domain.auth.repository.OneTimeTokenRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * 메일 링크에 실리는 1회용 토큰의 발급과 무효화 골격이다(S15P21E106-284).
 *
 * <p>토큰은 의미가 없는 난수 32바이트를 Base64URL(패딩 없음)로 인코딩한 문자열이고, DB에는 SHA-256 해시만 저장한다(플랜 결정 8). 원문은 메일의
 * 링크에만 실리므로 서버는 발급 시점 외에는 원문을 알지 못한다.
 *
 * <p><b>모든 공개 메서드는 호출자의 트랜잭션 안에서 동작한다.</b> 토큰 저장과 계정 생성·상태 변경이 한 트랜잭션이어야 하고, 하위 클래스가 트랜잭션 완료 시점에
 * 기대는 장치({@link #beforeInvalidate})를 걸 수 있어야 하기 때문이다.
 *
 * <p>종류마다 다른 것은 유효기간, 저장할 엔티티, 그리고 무효화 전에 걸 직렬화뿐이다. 그 셋만 하위 클래스가 정한다.
 */
public abstract class OneTimeTokenIssuer<T extends OneTimeTokenEntity> {

    private static final int TOKEN_BYTES = 32;

    private final OneTimeTokenRepository<T> tokenRepository;
    private final Duration tokenTtl;
    private final SecureRandom random = new SecureRandom();

    protected OneTimeTokenIssuer(OneTimeTokenRepository<T> tokenRepository, Duration tokenTtl) {
        this.tokenRepository = tokenRepository;
        this.tokenTtl = tokenTtl;
    }

    /**
     * 기존 미사용 토큰을 무효화하고 새 토큰을 발급한다.
     *
     * <p>{@code final}이다. 하위 클래스가 이 메서드를 재정의하면 {@link #invalidateUnused} 호출이 사라져 이전 링크 무효화
     * (API-003·API-010 처리 규칙)와 종류별 직렬화({@link #beforeInvalidate})가 함께 조용히 없어진다(284 리뷰 B-1). 종류마다
     * 달라지는 것은 유효기간·엔티티·훅 셋뿐이고 그 셋은 생성자와 {@link #newToken}·{@link #beforeInvalidate}로 이미 열려 있다.
     *
     * @return 메일 링크에 실을 토큰 원문
     */
    public final String issue(Long userId) {
        Instant now = Instant.now();
        invalidateUnused(userId, now);

        String rawToken = generateRawToken();
        tokenRepository.save(newToken(userId, hashOf(rawToken), now.plus(tokenTtl)));
        return rawToken;
    }

    /** 저장된 해시와 비교할 수 있도록 토큰 원문을 같은 방식으로 변환한다. 저장과 조회가 같은 변환을 써야 하므로 재정의를 막는다. */
    public final String hashOf(String rawToken) {
        return Sha256Hasher.hex(rawToken);
    }

    /**
     * 다시 요청하면 이전 링크는 즉시 쓸 수 없어야 한다. 유효한 링크가 여러 개 떠 있으면 1회용의 의미가 약해진다(API-003·API-010 처리 규칙).
     *
     * <p>조회 전에 {@link #beforeInvalidate}를 부른다. 하위 클래스가 사용자 단위 직렬화를 걸 지점이다.
     */
    protected final void invalidateUnused(Long userId, Instant now) {
        beforeInvalidate(userId);
        tokenRepository
                .findAllByUserIdAndUsedAtIsNull(userId)
                .forEach(token -> token.markUsed(now));
    }

    /**
     * 무효화 조회 직전에 불린다. 기본 구현은 아무것도 하지 않는다.
     *
     * <p>행 잠금만으로 부족한 종류가 이 자리에서 직렬화를 건다. 인증 토큰에는 무효화만 하는 별도 경로가 없어 기본 구현을 그대로 쓴다.
     */
    protected void beforeInvalidate(Long userId) {}

    /** 종류별 엔티티를 만든다. 저장은 이 클래스가 한다. */
    protected abstract T newToken(Long userId, String tokenHash, Instant expiresAt);

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
