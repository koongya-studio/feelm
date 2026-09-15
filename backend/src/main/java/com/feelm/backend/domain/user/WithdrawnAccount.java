package com.feelm.backend.domain.user;

import java.util.Locale;

/**
 * 탈퇴 계정의 익명화 치환 규칙(D-9)의 단일 출처.
 *
 * <p>탈퇴(API-013)가 값을 만들고, 가입 두 경로(API-001·008)가 같은 규칙으로 선점을 막는다. 규칙이 갈라지면 어느 한쪽만 고쳤을 때 조용히 충돌한다.
 */
public final class WithdrawnAccount {

    /**
     * 치환 값의 접두사.
     *
     * <p>{@code -}는 닉네임 규칙({@link NicknamePolicy#PATTERN}: 한글·영문·숫자·밑줄)에 없는 문자라 살아 있는 닉네임과 겹치지 않는다.
     */
    private static final String PREFIX = "withdrawn-";

    /**
     * 치환 이메일의 도메인. RFC 2606이 예약한 {@code .invalid}라 실재하는 주소가 되지 않는다.
     *
     * <p>형식만으로는 가입할 수 있는 주소이므로 {@link #isReservedEmail}로 가입을 막는다. 막지 않으면 공격자가 대상의 치환 값을 미리 선점해 그
     * 사용자의 탈퇴를 유일 제약 위반으로 실패시킬 수 있다(리뷰 A-F3·B-F2).
     */
    private static final String EMAIL_DOMAIN = "@feelm.invalid";

    private WithdrawnAccount() {}

    /**
     * 탈퇴 계정의 치환 이메일.
     *
     * <p>{@code users.email}은 255자라 여유가 충분하다.
     */
    public static String email(long userId) {
        return PREFIX + userId + EMAIL_DOMAIN;
    }

    /**
     * 탈퇴 계정의 치환 닉네임.
     *
     * <p>{@code users.nickname}은 20자이고 접두사가 10자이므로 10자리 식별자까지 들어간다. 식별자를 붙여 탈퇴 계정끼리도 겹치지 않는다.
     */
    public static String nickname(long userId) {
        return PREFIX + userId;
    }

    /** 가입이 선점할 수 없는 예약 이메일인지. 정규화(소문자) 여부와 무관하게 판정한다. */
    public static boolean isReservedEmail(String email) {
        return email != null && email.trim().toLowerCase(Locale.ROOT).endsWith(EMAIL_DOMAIN);
    }
}
