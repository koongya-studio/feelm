package com.feelm.backend.domain.taste.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.taste.repository.FrameCommandRepository;
import com.feelm.backend.domain.taste.repository.FrameCommandRepository.CreatedFrame;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FrameWriterTest {

    @Mock private FrameCommandRepository frameCommandRepository;
    @Mock private EntityManager entityManager;
    @InjectMocks private FrameWriter frameWriter;

    @Test
    void flushesPendingJpaRatingBeforeJdbcFrameInsertAndNormalizesTime() {
        Instant input = Instant.parse("2026-09-16T01:23:45.123456700Z");
        Instant normalized = Instant.parse("2026-09-16T01:23:45.123456Z");
        when(frameCommandRepository.create(31, 11, 21, normalized))
                .thenReturn(Optional.of(new CreatedFrame(41, normalized)));

        var created = frameWriter.create(31, 11, 21, input);

        InOrder order = inOrder(entityManager, frameCommandRepository);
        order.verify(entityManager).flush();
        order.verify(frameCommandRepository).create(31, 11, 21, normalized);
        assertThat(created.frameId()).isEqualTo(41);
        assertThat(created.createdAt()).isEqualTo(normalized);
    }
}
