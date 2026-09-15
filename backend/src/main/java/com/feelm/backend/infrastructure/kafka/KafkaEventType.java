package com.feelm.backend.infrastructure.kafka;

/** 추천 파이프라인에 전달하는 이벤트 종류와 계약 버전별 Kafka Topic을 함께 정의한다. */
public enum KafkaEventType {
    RATING("feelm.rating.v1"),
    ONBOARDING("feelm.rating.v1"),
    DISMISS("feelm.dismiss.v1"),
    VIEW("feelm.view.v1"),
    RECOMMENDATION_CLICK("feelm.recommendation-click.v1"),
    OTT_CLICK("feelm.ott-click.v1");

    private final String topic;

    KafkaEventType(String topic) {
        this.topic = topic;
    }

    /** 이 이벤트를 기록할 Kafka Topic 이름을 반환한다. */
    public String topic() {
        return topic;
    }
}
