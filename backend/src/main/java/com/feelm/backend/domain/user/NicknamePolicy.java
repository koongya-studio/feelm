package com.feelm.backend.domain.user;

import java.util.regex.Pattern;

/**
 * 닉네임 규칙의 단일 출처.
 *
 * <p>이메일 가입(API-001), 소셜 가입(API-008), 프로필 수정(API-015), 사용 가능 확인(API-016)이 모두 이 규칙을 쓴다. 규칙이 경로별로
 * 갈라지면 어떤 화면으로 들어왔는지에 따라 통과하는 닉네임이 달라지므로 상수를 한 곳에만 둔다.
 *
 * <p>형식과 별개로 <b>중복</b> 판정의 정규화 규칙(공백 제거·소문자)은 DB의 함수 기반 유일 인덱스 {@code
 * uix_users_nickname_normalized}가 정본이며, 질의는 {@code UserRepository#countByNormalizedNickname}이 같은
 * 식으로 수행한다.
 */
public final class NicknamePolicy {

    /**
     * 2~10자, 한글·영문·숫자·밑줄(S15P21E106-149 플랜 결정 2). 공백은 허용하지 않는다.
     *
     * <p>Bean Validation의 {@code @Pattern}에 쓰이므로 컴파일 시점 상수여야 한다.
     */
    public static final String PATTERN = "^[가-힣a-zA-Z0-9_]{2,10}$";

    /** 형식 위반 시 사용자에게 보여 줄 메시지. 검증 위치가 달라도 같은 문구를 쓴다. */
    public static final String MESSAGE = "닉네임은 2~10자이며 한글·영문·숫자·밑줄만 사용할 수 있습니다.";

    private static final Pattern COMPILED = Pattern.compile(PATTERN);

    private NicknamePolicy() {}

    /** 형식을 만족하는지. 값이 없으면 만족하지 않는 것으로 본다(API-016은 형식 위반으로 응답한다). */
    public static boolean isValidFormat(String nickname) {
        return nickname != null && COMPILED.matcher(nickname).matches();
    }
}
