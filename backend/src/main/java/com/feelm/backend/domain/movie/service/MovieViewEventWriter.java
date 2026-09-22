package com.feelm.backend.domain.movie.service;

import com.feelm.backend.domain.movie.event.ViewEventPayload;
import com.feelm.backend.domain.user.service.UserEventEligibility;
import com.feelm.backend.infrastructure.kafka.KafkaEventPublisher;
import com.feelm.backend.infrastructure.kafka.KafkaEventType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 상세 조회의 읽기 트랜잭션과 분리해 VIEW Outbox를 기록한다. */
@Component
@RequiredArgsConstructor
public class MovieViewEventWriter {

    private final KafkaEventPublisher eventPublisher;
    private final UserEventEligibility userEventEligibility;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean record(long userId, long movieId) {
        if (!userEventEligibility.lockActive(userId)) {
            return false;
        }
        eventPublisher.publish(KafkaEventType.VIEW, userId, new ViewEventPayload(movieId));
        return true;
    }
}
