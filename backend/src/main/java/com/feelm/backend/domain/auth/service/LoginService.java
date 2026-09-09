package com.feelm.backend.domain.auth.service;

import com.feelm.backend.domain.auth.AuthRateLimiter;
import com.feelm.backend.domain.auth.RefreshTokenService;
import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.repository.UserRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** API-004 이메일 로그인. */
@Service
public class LoginService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final AuthRateLimiter rateLimiter;

    /**
     * 계정이 없을 때 대조에 쓰는 해시.
     *
     * <p>계정이 없으면 비밀번호 대조를 건너뛰게 되는데, BCrypt 대조에는 수십 밀리초가 걸려 응답 시간만으로 가입 여부를 구분할 수 있다. 무작위 값의 해시와 대조해
     * 두 경로의 처리량을 맞춘다. 기동할 때마다 새로 만들므로 소스에 고정 값을 두지 않는다.
     */
    private final String absentAccountPasswordHash;

    public LoginService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            RefreshTokenService refreshTokenService,
            AuthRateLimiter rateLimiter) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.rateLimiter = rateLimiter;
        this.absentAccountPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * 자격을 확인하고 토큰 한 쌍을 발급한다.
     *
     * <p>없는 이메일·비밀번호 불일치·탈퇴 계정을 모두 {@code INVALID_CREDENTIALS}로 응답해 어떤 계정이 존재하는지 드러내지 않는다. 미인증 계정만
     * {@code EMAIL_NOT_VERIFIED}로 구분하는데, 이는 비밀번호를 맞힌 본인에게만 보여 주는 안내다.
     *
     * <p>연속 실패 제한은 대조 전에 시도를 세는 방식이다. 제한을 넘은 시도는 비밀번호를 보지 않고 429가 되고, 자격이 확인된 시도만 이메일 카운터를 지운다. 그래서
     * 동시 요청이 몰려도 한 창 안에서 대조는 제한 횟수만 일어나며, 미인증 계정이 맞는 비밀번호로 반복 시도해도 차단되지 않는다.
     *
     * <p>제한은 이메일 축과 클라이언트 IP 축을 함께 본다(S15P21E106-312). IP 축을 먼저 보므로 IP 예산을 넘긴 요청은 이메일 카운터를 소모하지 않고,
     * 두 축의 429는 같은 {@code RATE_LIMITED} 응답이라 어느 축에서 막혔는지도 가입 여부도 드러내지 않는다. 축과 순서의 근거는 {@link
     * AuthRateLimiter} 주석에 있다.
     *
     * @param clientIp 요청을 보낸 클라이언트 IP. IP 단위 시도 제한에 쓴다
     */
    @Transactional(readOnly = true)
    public LoginResult login(String rawEmail, String password, String clientIp) {
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (!rateLimiter.tryAcquireLoginAttempt(email, clientIp)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }

        Optional<User> found = userRepository.findByEmail(email);
        if (!matches(password, found) || found.map(User::isWithdrawn).orElse(true)) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        rateLimiter.clearLoginAttempts(email);

        User user = found.orElseThrow();
        if (user.isPendingVerification()) {
            throw new BusinessException(ErrorCode.EMAIL_NOT_VERIFIED);
        }

        RefreshTokenService.IssuedTokens tokens = refreshTokenService.issue(user.getId());
        return LoginResult.of(tokens, user);
    }

    /** 계정이 없거나 익명화로 해시가 지워진 계정도 대조 자체는 수행해 처리 시간을 맞춘다. */
    private boolean matches(String password, Optional<User> found) {
        return passwordEncoder.matches(
                password, found.map(User::getPasswordHash).orElse(absentAccountPasswordHash));
    }

    /** 로그인 결과. Refresh Token은 쿠키로만 나가므로 응답 DTO와 분리해 전달한다. */
    public record LoginResult(
            String accessToken,
            String refreshToken,
            long expiresIn,
            Long userId,
            String nickname,
            boolean onboardingCompleted) {

        /** 발급한 토큰과 사용자로 결과를 만든다. 이메일 로그인·소셜 로그인·소셜 가입이 모두 같은 모양의 결과를 만들기 때문에 공유한다. */
        static LoginResult of(RefreshTokenService.IssuedTokens tokens, User user) {
            return new LoginResult(
                    tokens.accessToken(),
                    tokens.refreshToken(),
                    tokens.expiresIn(),
                    user.getId(),
                    user.getNickname(),
                    user.isOnboardingCompleted());
        }
    }
}
