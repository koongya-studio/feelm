package com.feelm.backend.domain.user;

import java.util.Locale;
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

    /** 닉네임 최소 길이. 사용자 검색(API-019)의 검색어 하한도 이 값을 쓴다. */
    public static final int MIN_LENGTH = 2;

    /** 닉네임 최대 길이. 이보다 긴 검색어는 어떤 닉네임과도 일치할 수 없다. */
    public static final int MAX_LENGTH = 10;

    /**
     * 2~10자, 한글·영문·숫자·밑줄(S15P21E106-149 플랜 결정 2). 공백은 허용하지 않는다.
     *
     * <p>Bean Validation의 {@code @Pattern}에 쓰이므로 컴파일 시점 상수여야 한다. 길이는 {@link #MIN_LENGTH}·{@link
     * #MAX_LENGTH}에서 조립해 상수와 정규식이 따로 놀지 않게 한다.
     */
    public static final String PATTERN = "^[가-힣a-zA-Z0-9_]{" + MIN_LENGTH + "," + MAX_LENGTH + "}$";

    /** 형식 위반 시 사용자에게 보여 줄 메시지. 검증 위치가 달라도 같은 문구를 쓴다. */
    public static final String MESSAGE = "닉네임은 2~10자이며 한글·영문·숫자·밑줄만 사용할 수 있습니다.";

    private static final Pattern COMPILED = Pattern.compile(PATTERN);

    private NicknamePolicy() {}

    /** 형식을 만족하는지. 값이 없으면 만족하지 않는 것으로 본다(API-016은 형식 위반으로 응답한다). */
    public static boolean isValidFormat(String nickname) {
        return nickname != null && COMPILED.matcher(nickname).matches();
    }

    /**
     * 비교용 정규화 값(공백 제거·소문자). 유일 인덱스 {@code uix_users_nickname_normalized}의 {@code
     * lower(replace(nickname, ' ', ''))}와 같은 규칙이며, 사용자 검색(API-019)이 검색어를 이 형태로 바꿔 질의에 넘긴다.
     *
     * <p>{@code Locale.ROOT}로 소문자화해 터키어 {@code i}처럼 로케일에 따라 달라지는 변환을 배제한다. 닉네임에 허용된 문자(한글·영문·숫자·밑줄)
     * 범위에서는 PostgreSQL {@code lower()}와 결과가 같다.
     *
     * @return 값이 없으면 {@code null}
     */
    public static String normalize(String nickname) {
        return nickname == null ? null : nickname.replace(" ", "").toLowerCase(Locale.ROOT);
    }

    /**
     * 저장된 닉네임에 결코 들어갈 수 없는 제어문자가 섞여 있는지.
     *
     * <p>형식 검증과 별개로 필요한 판정이다. PostgreSQL의 {@code text}는 NUL 문자를 담지 못해, 제어문자가 섞인 값을 그대로 질의 파라미터로 넘기면
     * 드라이버가 거절하고 클라이언트 입력이 500 응답이 된다. 사용자 검색(API-019)의 검색어와 커서가 이 판정을 거친다.
     *
     * @return 값이 없으면 {@code false}
     */
    public static boolean containsControlCharacter(String value) {
        return value != null && value.chars().anyMatch(Character::isISOControl);
    }
}
