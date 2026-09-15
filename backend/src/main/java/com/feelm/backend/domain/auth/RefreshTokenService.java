package com.feelm.backend.domain.auth;

import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.repository.UserRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.global.security.TokenProvider;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Refresh Token의 발급, 회전, 폐기를 담당한다. */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final TokenProvider tokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;

    public RefreshTokenService(
            TokenProvider tokenProvider,
            RefreshTokenRepository refreshTokenRepository,
            UserRepository userRepository) {
        this.tokenProvider = tokenProvider;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
    }

    /** 로그인처럼 신원이 이미 확인된 시점에 토큰 한 쌍을 새로 발급한다. */
    public IssuedTokens issue(long userId) {
        String tokenId = UUID.randomUUID().toString();
        refreshTokenRepository.save(userId, tokenId);
        return createTokens(userId, tokenId);
    }

    /**
     * Refresh Token을 회전한다. 쓰인 토큰은 즉시 폐기하고 새 토큰 한 쌍을 발급한다.
     *
     * <p>서명과 만료가 정상인데 저장소에 없다면 이미 회전으로 폐기된 토큰이 다시 들어온 것이다. 정상 사용자의 재시도일 수도 있으나 탈취된 토큰이 쓰이는 중일 수도
     * 있으므로, 후자를 가정하고 해당 사용자의 Refresh Token을 모두 폐기한다(API-006 처리 규칙). 정상 사용자는 다시 로그인하면 되지만 공격자도 함께
     * 끊긴다.
     *
     * <p>기존 토큰 소비와 새 토큰 등록을 {@link RefreshTokenRepository#rotate} 한 번으로 처리한다. 확인·삭제·등록을 나누면 같은 토큰을
     * 든 두 요청이 동시에 도착했을 때 재사용 감지가 무력화되거나, 재사용 감지의 전량 폐기 뒤에 새 토큰이 저장될 수 있다.
     */
    public IssuedTokens rotate(String refreshToken) {
        TokenProvider.RefreshTokenClaims claims = tokenProvider.parseRefreshToken(refreshToken);
        long userId = claims.userId();
        rejectInactiveAccount(userId);

        String newTokenId = UUID.randomUUID().toString();
        if (!refreshTokenRepository.rotate(userId, claims.tokenId(), newTokenId)) {
            log.warn("폐기된 Refresh Token이 재사용되어 사용자 {}의 모든 토큰을 폐기합니다.", userId);
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        return createTokens(userId, newTokenId);
    }

    /**
     * 활성 계정이 아니면 회전을 거절하고 남은 토큰을 모두 폐기한다.
     *
     * <p>Refresh Token에는 계정 상태가 없어 서명과 저장소만 보면 탈퇴한 계정의 세션이 14일 만료까지 계속 갱신된다. 특히 탈퇴와 동시에 진행되던 로그인이
     * 발급한 토큰은 탈퇴의 전량 폐기를 앞지를 수 있어(리뷰 A-F4·B-F3) 저장소에 남는데, 이 검사가 없으면 그 한 건으로 세션이 무기한 연장된다.
     *
     * <p>거절하면서 남은 토큰을 지우는 이유는 같은 사용자의 다른 기기 토큰도 같은 이유로 무효이기 때문이다. 다시 로그인하면 되지만 탈퇴한 계정은 로그인 자체가 막힌다.
     */
    private void rejectInactiveAccount(long userId) {
        boolean active = userRepository.findById(userId).filter(User::isActive).isPresent();
        if (!active) {
            refreshTokenRepository.deleteAll(userId);
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
    }

    /** 현재 세션의 Refresh Token만 폐기한다. 로그아웃(API-005)이 쓴다. */
    public void revoke(String refreshToken) {
        TokenProvider.RefreshTokenClaims claims = tokenProvider.parseRefreshToken(refreshToken);
        refreshTokenRepository.deleteIfPresent(claims.userId(), claims.tokenId());
    }

    /** 해당 사용자의 Refresh Token을 모두 폐기한다. 비밀번호 변경·탈퇴 등 전 기기 로그아웃이 필요한 곳에서 쓴다. */
    public void revokeAll(long userId) {
        refreshTokenRepository.deleteAll(userId);
    }

    private IssuedTokens createTokens(long userId, String tokenId) {
        return new IssuedTokens(
                tokenProvider.createAccessToken(userId),
                tokenProvider.createRefreshToken(userId, tokenId),
                tokenProvider.accessTokenTtlSeconds());
    }

    /** 발급 결과. {@code expiresIn}은 Access Token의 수명(초)이며 API-006 응답 본문에 그대로 쓰인다. */
    public record IssuedTokens(String accessToken, String refreshToken, long expiresIn) {}
}
