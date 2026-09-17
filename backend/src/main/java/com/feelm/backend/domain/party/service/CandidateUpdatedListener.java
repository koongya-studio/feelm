package com.feelm.backend.domain.party.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Candidate 게시 완료 알림을 현재 활성 파티의 지속성 있는 refresh 요청으로 바꾼다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class CandidateUpdatedListener {
    private final ObjectMapper objectMapper;
    private final CandidateUpdatedRequestService requestService;

    @KafkaListener(
            topics = "feelm.candidate-updated.v1",
            groupId = "feelm-party-refresh-v1",
            containerFactory = "candidateUpdatedListenerFactory",
            autoStartup = "${feelm.party.refresh.consumer-enabled:true}")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        Long userId = userId(record);
        if (userId != null) {
            requestService.requestCurrentParties(userId);
        } else {
            log.warn("Discarding invalid candidate-updated message at offset {}", record.offset());
        }
        // 위 서비스 트랜잭션이 커밋된 후에만 ACK한다.
        acknowledgment.acknowledge();
    }

    private Long userId(ConsumerRecord<String, String> record) {
        try {
            if (record.key() == null || record.value() == null) {
                return null;
            }
            JsonNode body = objectMapper.readTree(record.value());
            JsonNode id = body.path("userId");
            if (!body.isObject()
                    || body.size() != 1
                    || !id.isIntegralNumber()
                    || !id.canConvertToLong()
                    || id.longValue() <= 0
                    || !record.key().equals(Long.toString(id.longValue()))) {
                return null;
            }
            return id.longValue();
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
