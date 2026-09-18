package com.feelm.backend.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.recommendation.model.RecommendationBufferResult;
import org.junit.jupiter.api.Test;

class RecommendationBufferNoticeTest {
    @Test
    void submitsReasonCompletionOnlyAfterBufferWriterReturns() {
        RecommendationBufferWriter writer = mock(RecommendationBufferWriter.class);
        RecommendationReasonService reasons = mock(RecommendationReasonService.class);
        RecommendationReasonDispatcher dispatcher = mock(RecommendationReasonDispatcher.class);
        RecommendationBufferService service =
                new RecommendationBufferService(writer, reasons, dispatcher);
        when(writer.writeCurrentFromNotice(1024L)).thenReturn(new RecommendationBufferResult(42L));

        assertThat(service.createBufferFromNotice(1024L).recommendationSetId()).isEqualTo(42L);

        var order = inOrder(writer, dispatcher);
        order.verify(writer).writeCurrentFromNotice(1024L);
        order.verify(dispatcher).submit(42L);
        verify(reasons, never()).completeUnexposed(42L);
    }
}
