package com.feelm.backend.domain.party.service;

import com.feelm.backend.domain.party.model.PartyCandidatePublication;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Redis 파티 후보 게시 완료 알림을 추천 세트와 분리된 DB 상태로 기록한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PartyCandidateUpdatedListener {
    private static final Pattern INPUT_ID = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern MODEL_VERSION = Pattern.compile("[a-z0-9][a-z0-9._-]{0,49}");

    private final ObjectMapper objectMapper;
    private final PartyCandidatePublicationService publicationService;

    @KafkaListener(
            topics = "feelm.party-candidate-updated.v1",
            groupId = "feelm-party-candidate-updated-v1",
            containerFactory = "partyCandidateUpdatedListenerFactory",
            autoStartup = "${feelm.party.candidate-updated.consumer-enabled:true}")
    public void onMessage(ConsumerRecord<byte[], String> record, Acknowledgment acknowledgment) {
        PartyCandidatePublication publication = publication(record);
        if (publication == null) {
            log.warn(
                    "Discarding invalid party-candidate-updated message at offset {}",
                    record.offset());
        } else {
            publicationService.record(publication);
        }
        // 서비스 트랜잭션이 커밋되거나 잘못된 입력을 명시적으로 폐기한 뒤에만 ACK한다.
        acknowledgment.acknowledge();
    }

    private PartyCandidatePublication publication(ConsumerRecord<byte[], String> record) {
        try {
            Long keyPartyId = partyId(record.key());
            if (keyPartyId == null || record.value() == null) {
                return null;
            }
            JsonNode body = objectMapper.readTree(record.value());
            JsonNode partyId = body.path("partyId");
            JsonNode inputId = body.path("inputId");
            JsonNode modelVersion = body.path("modelVersion");
            JsonNode generatedAt = body.path("generatedAt");
            JsonNode sourcePartition = body.path("sourcePartition");
            JsonNode sourceOffset = body.path("sourceOffset");
            if (!body.isObject()
                    || body.size() != 6
                    || !partyId.isIntegralNumber()
                    || !partyId.canConvertToLong()
                    || partyId.longValue() <= 0
                    || partyId.longValue() != keyPartyId
                    || !inputId.isTextual()
                    || !INPUT_ID.matcher(inputId.asString()).matches()
                    || !modelVersion.isTextual()
                    || !MODEL_VERSION.matcher(modelVersion.asString()).matches()
                    || !generatedAt.isTextual()
                    || !generatedAt.asString().endsWith("Z")
                    || !sourcePartition.isIntegralNumber()
                    || !sourcePartition.canConvertToInt()
                    || sourcePartition.intValue() < 0
                    || !sourceOffset.isIntegralNumber()
                    || !sourceOffset.canConvertToLong()
                    || sourceOffset.longValue() < 0) {
                return null;
            }
            return new PartyCandidatePublication(
                    partyId.longValue(),
                    inputId.asString(),
                    modelVersion.asString(),
                    Instant.parse(generatedAt.asString()),
                    sourcePartition.intValue(),
                    sourceOffset.longValue());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static Long partyId(byte[] key) {
        if (key == null || key.length != Long.BYTES) {
            return null;
        }
        long partyId = ByteBuffer.wrap(key).getLong();
        return partyId > 0 ? partyId : null;
    }
}
