package com.feelm.backend.domain.user.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * 알림 수신 설정 변경 요청(API-018). 세 항목을 전체 교체한다.
 *
 * <p>명세가 세 키 중 누락을 {@code VALIDATION_FAILED}로 정의하므로 {@code boolean}이 아니라 {@code Boolean}으로 받아 키 누락과
 * {@code null}을 모두 검증 실패로 판정한다. {@code boolean}이면 누락이 {@code false}로 채워져 사용자가 보내지 않은 값을 저장하게 된다.
 */
public record NotificationSettingsUpdateRequest(
        @JsonProperty("RATING") @NotNull(message = "평가 알림 수신 여부를 입력해 주세요.") Boolean rating,
        @JsonProperty("RECOMMENDATION") @NotNull(message = "추천 알림 수신 여부를 입력해 주세요.")
                Boolean recommendation,
        @JsonProperty("MARKETING") @NotNull(message = "마케팅 알림 수신 여부를 입력해 주세요.")
                Boolean marketing) {}
