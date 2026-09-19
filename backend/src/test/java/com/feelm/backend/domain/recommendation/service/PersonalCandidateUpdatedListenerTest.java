package com.feelm.backend.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.json.JsonMapper;

class PersonalCandidateUpdatedListenerTest {
    private final RecommendationBufferService bufferService =
            mock(RecommendationBufferService.class);
    private final PersonalCandidateUpdatedListener listener =
            new PersonalCandidateUpdatedListener(JsonMapper.builder().build(), bufferService);

    @Test
    void acknowledgesOnlyAfterBufferCreation() {
        Acknowledgment ack = mock(Acknowledgment.class);

        listener.onMessage(record("1024", "{\"userId\":1024}"), ack);

        var order = org.mockito.Mockito.inOrder(bufferService, ack);
        order.verify(bufferService).createBufferFromNotice(1024L);
        order.verify(ack).acknowledge();
    }

    @Test
    void acknowledgesMalformedNoticesWithoutCreatingBuffer() {
        Acknowledgment ack = mock(Acknowledgment.class);

        listener.onMessage(record(null, "{\"userId\":1024}"), ack);
        listener.onMessage(record("1025", "{\"userId\":1024}"), ack);
        listener.onMessage(record("1024", "{\"userId\":1024,\"eventId\":\"x\"}"), ack);
        listener.onMessage(record("1024", "{"), ack);
        listener.onMessage(record("1024", null), ack);

        verify(bufferService, never()).createBufferFromNotice(1024L);
        verify(ack, times(5)).acknowledge();
    }

    @Test
    void acknowledgesInactiveUser() {
        Acknowledgment ack = mock(Acknowledgment.class);
        doThrow(new BusinessException(ErrorCode.UNAUTHORIZED))
                .when(bufferService)
                .createBufferFromNotice(1024L);

        listener.onMessage(record("1024", "{\"userId\":1024}"), ack);

        verify(ack).acknowledge();
    }

    @Test
    void acknowledgesValidNoticeWithoutEligibleMovies() {
        Acknowledgment ack = mock(Acknowledgment.class);
        doThrow(new NoEligibleRecommendationCandidatesException())
                .when(bufferService)
                .createBufferFromNotice(1024L);

        listener.onMessage(record("1024", "{\"userId\":1024}"), ack);

        verify(ack).acknowledge();
    }

    @Test
    void keepsOffsetUncommittedOnCandidateFailure() {
        Acknowledgment ack = mock(Acknowledgment.class);
        doThrow(new BusinessException(ErrorCode.RECOMMENDATION_UNAVAILABLE))
                .when(bufferService)
                .createBufferFromNotice(1024L);

        assertThatThrownBy(() -> listener.onMessage(record("1024", "{\"userId\":1024}"), ack))
                .isInstanceOf(BusinessException.class);

        verify(ack, never()).acknowledge();
    }

    @Test
    void keepsOffsetUncommittedOnDatabaseFailure() {
        Acknowledgment ack = mock(Acknowledgment.class);
        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .when(bufferService)
                .createBufferFromNotice(1024L);

        assertThatThrownBy(() -> listener.onMessage(record("1024", "{\"userId\":1024}"), ack))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(ack, never()).acknowledge();
    }

    private static ConsumerRecord<String, String> record(String key, String value) {
        return new ConsumerRecord<>("feelm.candidate-updated.v1", 0, 1L, key, value);
    }
}
