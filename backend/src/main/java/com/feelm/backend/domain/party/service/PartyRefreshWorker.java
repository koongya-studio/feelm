package com.feelm.backend.domain.party.service;

import com.feelm.backend.domain.party.entity.Party;
import com.feelm.backend.domain.party.entity.PartyStatus;
import com.feelm.backend.domain.party.model.PartyInputSnapshot;
import com.feelm.backend.domain.party.model.PartyRefreshPayload;
import com.feelm.backend.domain.party.repository.PartyMemberRepository;
import com.feelm.backend.domain.party.repository.PartyRefreshRequestRepository;
import com.feelm.backend.domain.party.repository.PartyRepository;
import com.feelm.backend.infrastructure.kafka.KafkaEventPublisher;
import com.feelm.backend.infrastructure.redis.RedisPartyInputReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** DB에 남은 파티 요청을 순서대로 현재 입력으로 구체화한다. */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "feelm.party.refresh",
        name = "worker-enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class PartyRefreshWorker {
    private final PartyRefreshRequestRepository requestRepository;
    private final PartyRefreshProcessor processor;

    @Scheduled(fixedDelayString = "${feelm.party.refresh.poll-interval:1s}")
    public void processDue() {
        for (long partyId : requestRepository.findDue(Instant.now(), 100)) {
            try {
                processor.process(partyId);
            } catch (RuntimeException exception) {
                // DB 오류는 현재 요청을 지우지 않는다. 다음 폴링에서 다시 시도한다.
                log.warn(
                        "Party refresh request failed: partyId={}, cause={}",
                        partyId,
                        exception.getClass().getSimpleName());
            }
        }
    }
}

@Service
@RequiredArgsConstructor
class PartyRefreshProcessor {
    private final PartyRepository partyRepository;
    private final PartyMemberRepository memberRepository;
    private final PartyRefreshRequestRepository requestRepository;
    private final RedisPartyInputReader inputReader;
    private final KafkaEventPublisher eventPublisher;

    @Transactional
    public void process(long partyId) {
        // 모든 구성 변경도 먼저 party 행을 잠가 잠금 순서를 통일한다.
        Party party = partyRepository.findByIdForUpdate(partyId).orElse(null);
        if (party == null) {
            requestRepository.delete(partyId);
            return;
        }
        Long version = requestRepository.findVersionForUpdate(partyId).orElse(null);
        if (version == null) {
            return;
        }
        if (party.getStatus() != PartyStatus.ACTIVE) {
            requestRepository.delete(partyId);
            return;
        }
        List<Long> userIds = memberRepository.findUserIdsByPartyId(partyId);
        try {
            PartyInputSnapshot snapshot = inputReader.read(userIds);
            PartyRefreshPayload payload = payload(partyId, snapshot);
            eventPublisher.publishPartyRefresh(partyId, payload);
            requestRepository.delete(partyId);
        } catch (IllegalStateException | IllegalArgumentException exception) {
            // 불완전 게시·Redis 장애는 멤버십 변경을 되돌리지 않고 나중에 다시 읽는다.
            requestRepository.retry(partyId, version, Instant.now().plusSeconds(5));
        }
    }

    private static PartyRefreshPayload payload(long partyId, PartyInputSnapshot snapshot) {
        String inputId = inputId(partyId, snapshot);
        List<PartyRefreshPayload.Member> members =
                snapshot.members().stream()
                        .map(
                                member ->
                                        new PartyRefreshPayload.Member(
                                                member.userId(),
                                                member.vector(),
                                                member.candidateMovieIds()))
                        .toList();
        return new PartyRefreshPayload(partyId, inputId, members);
    }

    static String inputId(long partyId, PartyInputSnapshot snapshot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(partyId).array());
            update(digest, snapshot.modelVersion());
            for (PartyInputSnapshot.Member member : snapshot.members()) {
                digest.update(ByteBuffer.allocate(Long.BYTES).putLong(member.userId()).array());
                update(digest, member.generationId());
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
