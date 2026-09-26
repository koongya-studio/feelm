package com.feelm.backend.domain.notification.service;

/** 커밋 이후 해당 사용자의 알림 REST 정본을 다시 조회하게 한다. */
public record NotificationChangedEvent(long userId) {}
