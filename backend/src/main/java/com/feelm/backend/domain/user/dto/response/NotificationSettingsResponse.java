package com.feelm.backend.domain.user.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.feelm.backend.domain.user.entity.NotificationSettings;

/**
 * 알림 수신 설정 응답(API-017·018).
 *
 * <p>본문 키가 {@code NotificationType} 상태값과 같은 대문자라 필드 이름을 자바 규칙대로 두고 {@code @JsonProperty}로 명세 키에
 * 맞춘다.
 */
public record NotificationSettingsResponse(
        @JsonProperty("RATING") boolean rating,
        @JsonProperty("RECOMMENDATION") boolean recommendation,
        @JsonProperty("PARTY_INVITE") boolean partyInvite,
        @JsonProperty("REPORT_ISSUED") boolean reportIssued,
        @JsonProperty("MARKETING") boolean marketing) {

    public static NotificationSettingsResponse from(NotificationSettings settings) {
        return new NotificationSettingsResponse(
                settings.isRatingEnabled(),
                settings.isRecommendationEnabled(),
                settings.isPartyInviteEnabled(),
                settings.isReportIssuedEnabled(),
                settings.isMarketingEnabled());
    }
}
