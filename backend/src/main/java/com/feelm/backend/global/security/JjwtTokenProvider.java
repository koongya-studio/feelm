package com.feelm.backend.global.security;

import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.MacAlgorithm;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * jjwt로 {@link TokenProvider}를 구현한다. 이 프로젝트에서 JWT 라이브러리를 직접 다루는 유일한 클래스다.
 *
 * <p>서명은 HS256 대칭키를 쓴다. 백엔드가 하나뿐이라 키를 나눠 가질 대상이 없고, 검증 측과 발급 측이 동일하기 때문이다(ADR-0009).
 */
@Component
public class JjwtTokenProvider implements TokenProvider {

    private static final String CLAIM_TOKEN_TYPE = "typ";
    private static final String TOKEN_TYPE_ACCESS = "access";
    private static final String TOKEN_TYPE_REFRESH = "refresh";
    private static final String TOKEN_TYPE_SIGNUP = "signup";

    private static final String CLAIM_PROVIDER = "provider";
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_NICKNAME = "nickname";

    /** 서명 알고리즘. 키 길이에 따라 HS384·HS512로 바뀌지 않도록 고정한다. */
    private static final MacAlgorithm SIGNATURE_ALGORITHM = Jwts.SIG.HS256;

    /** {@link #SIGNATURE_ALGORITHM}에 대응하는 JCA 키 알고리즘 이름. */
    private static final String KEY_ALGORITHM = "HmacSHA256";

    /** HS256에 필요한 최소 키 길이. jjwt가 이보다 짧은 키를 거부한다. */
    private static final int MINIMUM_SECRET_BYTES = 32;

    private final SecretKey key;
    private final Duration accessTokenTtl;
    private final Duration refreshTokenTtl;
    private final Duration signupTokenTtl;

    public JjwtTokenProvider(JwtProperties properties) {
        this.key = toKey(properties.secret());
        this.accessTokenTtl = properties.accessTokenTtl();
        this.refreshTokenTtl = properties.refreshTokenTtl();
        this.signupTokenTtl = properties.signupTokenTtl();
    }

    /**
     * Secret을 HS256 서명 키로 만든다.
     *
     * <p>{@code Keys.hmacShaKeyFor}는 키 길이에 따라 알고리즘을 HS384·HS512로 올려 잡기 때문에 쓰지 않는다. Secret 길이를 바꿨다고
     * 서명 알고리즘이 조용히 달라지면 문서·계약과 실제 동작이 어긋난다.
     */
    private static SecretKey toKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "feelm.jwt.secret이 설정되지 않았습니다. JWT_SECRET 환경변수를 주입해야 합니다.");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException(
                    "feelm.jwt.secret이 너무 짧습니다. HS256은 최소 " + MINIMUM_SECRET_BYTES + "바이트가 필요합니다.");
        }
        return new SecretKeySpec(bytes, KEY_ALGORITHM);
    }

    @Override
    public String createAccessToken(long userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                .signWith(key, SIGNATURE_ALGORITHM)
                .compact();
    }

    @Override
    public String createRefreshToken(long userId, String tokenId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .id(tokenId)
                .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_REFRESH)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(refreshTokenTtl)))
                .signWith(key, SIGNATURE_ALGORITHM)
                .compact();
    }

    /**
     * 소셜 가입 토큰을 발급한다.
     *
     * <p>제공자가 주지 않은 값은 클레임에 넣지 않는다. jjwt는 {@code null} 값을 클레임에서 제거하므로 되읽을 때도 {@code null}이 된다.
     */
    @Override
    public String createSignupToken(SignupTokenClaims claims) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(claims.providerUserId())
                .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_SIGNUP)
                .claim(CLAIM_PROVIDER, claims.provider())
                .claim(CLAIM_EMAIL, claims.email())
                .claim(CLAIM_NICKNAME, claims.nickname())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(signupTokenTtl)))
                .signWith(key, SIGNATURE_ALGORITHM)
                .compact();
    }

    @Override
    public SignupTokenClaims parseSignupToken(String token) {
        Claims claims =
                parse(
                        token,
                        ErrorCode.SIGNUP_TOKEN_INVALID,
                        ErrorCode.SIGNUP_TOKEN_INVALID,
                        TOKEN_TYPE_SIGNUP);
        String providerUserId = claims.getSubject();
        String provider = claims.get(CLAIM_PROVIDER, String.class);
        if (providerUserId == null || providerUserId.isBlank() || provider == null) {
            throw new BusinessException(ErrorCode.SIGNUP_TOKEN_INVALID);
        }
        return new SignupTokenClaims(
                provider,
                providerUserId,
                claims.get(CLAIM_EMAIL, String.class),
                claims.get(CLAIM_NICKNAME, String.class));
    }

    @Override
    public long accessTokenTtlSeconds() {
        return accessTokenTtl.toSeconds();
    }

    @Override
    public long parseAccessToken(String token) {
        Claims claims =
                parse(token, ErrorCode.UNAUTHORIZED, ErrorCode.TOKEN_EXPIRED, TOKEN_TYPE_ACCESS);
        return toUserId(claims, ErrorCode.UNAUTHORIZED);
    }

    @Override
    public RefreshTokenClaims parseRefreshToken(String token) {
        Claims claims =
                parse(
                        token,
                        ErrorCode.REFRESH_TOKEN_INVALID,
                        ErrorCode.REFRESH_TOKEN_EXPIRED,
                        TOKEN_TYPE_REFRESH);
        String tokenId = claims.getId();
        if (tokenId == null || tokenId.isBlank()) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        return new RefreshTokenClaims(toUserId(claims, ErrorCode.REFRESH_TOKEN_INVALID), tokenId);
    }

    /**
     * 서명과 만료를 검증하고 토큰 종류까지 확인한다.
     *
     * <p>종류 확인이 없으면 Access Token을 Refresh Token 자리에 넣어 수명을 우회하는 등의 토큰 혼동이 가능해진다. 서명은 양쪽이 같은 키를 쓰므로
     * 서명만으로는 구분되지 않는다.
     */
    private Claims parse(String token, ErrorCode invalidCode, ErrorCode expiredCode, String type) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(invalidCode);
        }
        try {
            Claims claims =
                    Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            if (!type.equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
                throw new BusinessException(invalidCode);
            }
            return claims;
        } catch (ExpiredJwtException exception) {
            throw new BusinessException(expiredCode);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new BusinessException(invalidCode);
        }
    }

    private long toUserId(Claims claims, ErrorCode invalidCode) {
        try {
            return Long.parseLong(claims.getSubject());
        } catch (NumberFormatException exception) {
            throw new BusinessException(invalidCode);
        }
    }
}
