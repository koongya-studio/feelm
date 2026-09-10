package com.feelm.backend.domain.user.dto.response;

/**
 * API-016 닉네임 사용 가능 확인 응답.
 *
 * <p>{@code reason}은 사용할 수 없는 이유이며 사용 가능하면 {@code null}이다. 확인 결과는 예약이 아니므로 가입·수정 시점에 다시
 * 검사한다(API-016 처리 규칙).
 */
public record NicknameAvailabilityResponse(String nickname, boolean available, String reason) {

    private static final String DUPLICATED = "DUPLICATED";
    private static final String INVALID_FORMAT = "INVALID_FORMAT";

    public static NicknameAvailabilityResponse available(String nickname) {
        return new NicknameAvailabilityResponse(nickname, true, null);
    }

    public static NicknameAvailabilityResponse duplicated(String nickname) {
        return new NicknameAvailabilityResponse(nickname, false, DUPLICATED);
    }

    public static NicknameAvailabilityResponse invalidFormat(String nickname) {
        return new NicknameAvailabilityResponse(nickname, false, INVALID_FORMAT);
    }
}
