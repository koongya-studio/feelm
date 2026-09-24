package com.feelm.backend.domain.onboarding.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.onboarding.dto.request.OnboardingSubmitRequest;
import com.feelm.backend.domain.onboarding.entity.PreferenceChoice;
import com.feelm.backend.domain.onboarding.repository.OnboardingPreferenceRepository;
import com.feelm.backend.domain.taste.service.TasteAnalysisWriter;
import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.repository.UserRepository;
import com.feelm.backend.domain.watch.service.OnboardingRatingWriter;
import com.feelm.backend.infrastructure.kafka.KafkaEventPublisher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 온보딩 제출이 취향 분석 캐시를 <b>한 번만</b> 다시 계산하는지 본다(S15P21E106-775).
 *
 * <p>재계산을 평가 저장 지점({@code RatingStateWriter.create})에 두면 카드 수만큼 돈다. 카드가 10장이면 한 번의 제출에 10번 재계산되고,
 * 그동안 영화 10행을 {@code FOR UPDATE}로 쥔 채라 잠금 보유 시간이 그만큼 늘어난다.
 */
@DisplayName("온보딩 제출의 취향 분석 재계산")
class OnboardingSubmitServiceTest {

    private static final long USER_ID = 3L;
    private static final Instant NOW = Instant.parse("2026-09-24T01:00:00Z");

    private OnboardingRatingWriter onboardingRatingWriter;
    private TasteAnalysisWriter tasteAnalysisWriter;
    private OnboardingSubmitService service;

    @BeforeEach
    void setUp() {
        OnboardingPreferenceRepository preferenceRepository =
                mock(OnboardingPreferenceRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        onboardingRatingWriter = mock(OnboardingRatingWriter.class);
        tasteAnalysisWriter = mock(TasteAnalysisWriter.class);
        KafkaEventPublisher kafkaEventPublisher = mock(KafkaEventPublisher.class);
        User user = mock(User.class);
        when(user.isActive()).thenReturn(true);
        when(user.isOnboardingCompleted()).thenReturn(false);
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        service =
                new OnboardingSubmitService(
                        preferenceRepository,
                        userRepository,
                        onboardingRatingWriter,
                        tasteAnalysisWriter,
                        kafkaEventPublisher,
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("T5 카드를 열 장 골라도 재계산은 한 번이다")
    void recomputesOnceForWholeSubmission() {
        service.submit(USER_ID, submission(10));

        verify(onboardingRatingWriter).createMissing(anyLong(), any());
        verify(tasteAnalysisWriter, times(1)).refresh(USER_ID, NOW);
    }

    @Test
    @DisplayName("건너뛴 제출은 평가가 없어 재계산하지 않는다")
    void skipsRecomputeWhenNothingSubmitted() {
        service.submit(USER_ID, new OnboardingSubmitRequest(List.of(), true));

        verify(tasteAnalysisWriter, never()).refresh(anyLong(), any());
    }

    private static OnboardingSubmitRequest submission(int cards) {
        List<OnboardingSubmitRequest.Item> items =
                IntStream.rangeClosed(1, cards)
                        .mapToObj(
                                i ->
                                        new OnboardingSubmitRequest.Item(
                                                (long) i,
                                                i % 2 == 0
                                                        ? PreferenceChoice.LIKE
                                                        : PreferenceChoice.DISLIKE))
                        .toList();
        return new OnboardingSubmitRequest(items, false);
    }
}
