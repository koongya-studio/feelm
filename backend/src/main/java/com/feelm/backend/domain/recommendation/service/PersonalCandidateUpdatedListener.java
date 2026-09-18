package com.feelm.backend.domain.recommendation.service;

import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Candidate 게시 알림을 개인 추천 버퍼 생성으로 연결한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonalCandidateUpdatedListener {
    private final ObjectMapper objectMapper;
    private final RecommendationBufferService bufferService;

    @KafkaListener(
            topics = "feelm.candidate-updated.v1",
            groupId = "feelm-personal-buffer-v1",
            containerFactory = "personalCandidateUpdatedListenerFactory",
            autoStartup = "${feelm.recommendation.candidate-consumer-enabled:true}")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        Long userId = userId(record);
        if (userId == null) {
            log.warn(
                    "Discarding invalid personal candidate-updated message at offset {}",
                    record.offset());
            acknowledgment.acknowledge();
            return;
        }
        try {
            bufferService.createBufferFromNotice(userId);
        } catch (NoEligibleRecommendationCandidatesException exception) {
            log.info(
                    "Skipping candidate-updated message without eligible movies at offset {}",
                    record.offset());
        } catch (BusinessException exception) {
            if (exception.getErrorCode() != ErrorCode.UNAUTHORIZED) {
                throw exception;
            }
            log.info(
                    "Skipping candidate-updated message for inactive user at offset {}",
                    record.offset());
        }
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
