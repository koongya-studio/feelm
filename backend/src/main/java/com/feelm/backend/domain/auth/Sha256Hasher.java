package com.feelm.backend.domain.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 되돌릴 필요가 없는 값을 SHA-256 16진 문자열로 바꾼다.
 *
 * <p>인증 토큰은 DB에 원문을 남기지 않기 위해(플랜 결정 8), 레이트 리밋 키는 이메일 원문을 Redis에 남기지 않기 위해 사용한다. 비밀번호에는 쓰지 않는다.
 * 비밀번호는 salt와 작업 계수가 있는 BCrypt를 사용한다.
 */
final class Sha256Hasher {

    private Sha256Hasher() {}

    static String hex(String value) {
        return HexFormat.of().formatHex(digest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }
}
