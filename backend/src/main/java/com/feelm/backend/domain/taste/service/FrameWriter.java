package com.feelm.backend.domain.taste.service;

import com.feelm.backend.domain.taste.repository.FrameCommandRepository;
import com.feelm.backend.domain.taste.repository.FrameCommandRepository.CreatedFrame;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FrameWriter {

    private final FrameCommandRepository frameCommandRepository;
    private final EntityManager entityManager;

    public FrameWriter(FrameCommandRepository frameCommandRepository, EntityManager entityManager) {
        this.frameCommandRepository = frameCommandRepository;
        this.entityManager = entityManager;
    }

    /** 호출자의 평가 저장 트랜잭션 안에서 프레임을 생성한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public FrameCreation create(long ratingId, long userId, long movieId, Instant createdAt) {
        Objects.requireNonNull(createdAt, "프레임 생성 시각은 필수입니다.");
        Instant createdAtMicros = createdAt.truncatedTo(ChronoUnit.MICROS);
        entityManager.flush();
        CreatedFrame created =
                frameCommandRepository
                        .create(ratingId, userId, movieId, createdAtMicros)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "프레임 생성 대상 평가가 없거나 이미 프레임이 존재합니다."));
        return new FrameCreation(created.frameId(), created.createdAt());
    }

    public record FrameCreation(long frameId, Instant createdAt) {}
}
