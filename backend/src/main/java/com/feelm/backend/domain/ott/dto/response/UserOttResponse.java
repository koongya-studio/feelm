package com.feelm.backend.domain.ott.dto.response;

import java.util.List;

/** API-026·027 내 구독 OTT 응답. 설정하지 않았으면 빈 배열이다. */
public record UserOttResponse(List<Long> ottIds) {}
