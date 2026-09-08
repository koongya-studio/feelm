package com.feelm.backend.domain.auth.dto.request;

import com.feelm.backend.domain.user.entity.Gender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;

/**
 * API-008 소셜 회원가입 완료 요청.
 *
 * <p>이메일과 제공자 정보는 {@code signupToken}에 들어 있으므로 본문으로 받지 않는다. 클라이언트가 보낸 값을 그대로 믿으면 다른 사람의 소셜 계정으로 가입할
 * 수 있기 때문이다.
 *
 * <p>닉네임·생년월일·성별 규칙은 이메일 가입(API-001)과 같다.
 */
public record OAuthSignupRequest(
        @NotBlank(message = "가입 토큰이 필요합니다.") String signupToken,
        @NotBlank(message = "닉네임을 입력해 주세요.")
                @Pattern(
                        regexp = SignupRequest.NICKNAME_PATTERN,
                        message = "닉네임은 2~10자이며 한글·영문·숫자·밑줄만 사용할 수 있습니다.")
                String nickname,
        @NotNull(message = "생년월일을 입력해 주세요.") LocalDate birthDate,
        @NotNull(message = "성별을 선택해 주세요.") Gender gender) {}
