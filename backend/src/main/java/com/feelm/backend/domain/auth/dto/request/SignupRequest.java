package com.feelm.backend.domain.auth.dto.request;

import com.feelm.backend.domain.user.entity.Gender;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * API-001 이메일 회원가입 요청.
 *
 * <p>비밀번호·닉네임 규칙은 플랜 결정 1·2, {@code gender} 필수는 결정 11을 따른다. {@code gender}를 문자열이 아닌 enum으로 받아 허용하지
 * 않는 값은 역직렬화 단계에서 걸러진다(결정 17).
 */
public record SignupRequest(
        @NotBlank(message = "이메일을 입력해 주세요.")
                @Email(message = "이메일 형식이 올바르지 않습니다.")
                @Size(max = 255, message = "이메일은 255자 이하여야 합니다.")
                String email,
        @NotBlank(message = "비밀번호를 입력해 주세요.")
                @Pattern(
                        regexp = SignupRequest.PASSWORD_PATTERN,
                        message = "비밀번호는 8~64자이며 영문·숫자·특수문자를 각각 1자 이상 포함해야 합니다.")
                String password,
        @NotBlank(message = "닉네임을 입력해 주세요.")
                @Pattern(
                        regexp = SignupRequest.NICKNAME_PATTERN,
                        message = "닉네임은 2~10자이며 한글·영문·숫자·밑줄만 사용할 수 있습니다.")
                String nickname,
        @NotNull(message = "생년월일을 입력해 주세요.") LocalDate birthDate,
        @NotNull(message = "성별을 선택해 주세요.") Gender gender) {

    /**
     * 8~64자, 영문·숫자·특수문자를 각각 1자 이상(플랜 결정 1).
     *
     * <p>허용 문자는 공백을 제외한 아스키 출력 가능 문자({@code !}~{@code ~})다. 그 집합에서 영문·숫자가 아닌 문자는 곧 특수문자이므로 특수문자 조건을
     * 문자 집합 나열 없이 표현할 수 있다.
     */
    static final String PASSWORD_PATTERN =
            "^(?=.*[A-Za-z])(?=.*[0-9])(?=.*[^A-Za-z0-9])[\\x21-\\x7E]{8,64}$";

    /**
     * 2~10자, 한글·영문·숫자·밑줄(플랜 결정 2). 공백은 허용하지 않는다.
     *
     * <p>소셜 가입(API-008)과 추천 닉네임 생성도 같은 규칙을 써야 하므로 공개한다. 규칙이 두 곳으로 갈라지면 가입 경로에 따라 통과하는 닉네임이 달라진다.
     */
    public static final String NICKNAME_PATTERN = "^[가-힣a-zA-Z0-9_]{2,10}$";
}
