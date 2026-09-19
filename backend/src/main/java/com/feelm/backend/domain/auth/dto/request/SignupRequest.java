package com.feelm.backend.domain.auth.dto.request;

import com.feelm.backend.domain.user.NicknamePolicy;
import com.feelm.backend.domain.user.entity.Gender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;

/**
 * API-001 이메일 회원가입 요청.
 *
 * <p>가입할 이메일은 요청 본문이 아니라 {@code emailVerificationToken}에서만 읽는다(S15P21E106-603). 본문 값을 믿으면 남의 이메일로
 * 가입할 수 있고, 소셜 회원가입(API-008)이 {@code signupToken}에서 이메일을 읽는 방식과 같다.
 *
 * <p>비밀번호·닉네임 규칙은 플랜 결정 1·2, {@code gender} 필수는 결정 11을 따른다. {@code gender}를 문자열이 아닌 enum으로 받아 허용하지
 * 않는 값은 역직렬화 단계에서 걸러진다(결정 17).
 */
public record SignupRequest(
        @NotBlank(message = "이메일 인증을 먼저 완료해 주세요.") String emailVerificationToken,
        @NotBlank(message = "비밀번호를 입력해 주세요.")
                @Pattern(
                        regexp = SignupRequest.PASSWORD_PATTERN,
                        message = "비밀번호는 8~64자이며 영문·숫자·특수문자를 각각 1자 이상 포함해야 합니다.")
                String password,
        @NotBlank(message = "닉네임을 입력해 주세요.")
                @Pattern(regexp = NicknamePolicy.PATTERN, message = NicknamePolicy.MESSAGE)
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
}
