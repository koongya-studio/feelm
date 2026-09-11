package com.feelm.backend.domain.ott.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * API-027 내 구독 OTT 설정 요청. 목록을 전체 교체하며 빈 배열이면 전체 해제다.
 *
 * <p>개수 상한은 명세에 없는 방어값이다. 지원 OTT가 다섯 개뿐이라 정상 요청은 상한에 닿지 않으며, 존재 검증의 {@code in} 절이 임의 크기로 커지는 것을 입력
 * 단계에서 막는다. 상한을 넘겨도 명세가 정의한 {@code VALIDATION_FAILED}로 응답한다.
 */
public record UserOttUpdateRequest(
        @NotNull(message = "구독 OTT 목록을 입력해 주세요.")
                @Size(max = 50, message = "구독 OTT는 50개까지 설정할 수 있습니다.")
                List<@NotNull @Positive Long> ottIds) {}
