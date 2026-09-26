package com.feelm.backend.domain.watch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.notification.service.NotificationCommandService;
import com.feelm.backend.domain.taste.service.FrameWriter;
import com.feelm.backend.domain.taste.service.FrameWriter.FrameCreation;
import com.feelm.backend.domain.taste.service.TasteAnalysisWriter;
import com.feelm.backend.domain.watch.dto.request.RatingCreateRequest;
import com.feelm.backend.domain.watch.dto.response.RatingCreateResponse;
import com.feelm.backend.domain.watch.model.RatingVisibility;
import com.feelm.backend.domain.watch.repository.RatingCommandRepository;
import com.feelm.backend.domain.watch.repository.RatingCommandRepository.CreatedRating;
import com.feelm.backend.domain.watch.repository.RatingCommandRepository.LockedMovie;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.infrastructure.kafka.KafkaEventPublisher;
import com.feelm.backend.infrastructure.kafka.KafkaEventType;
import com.feelm.backend.infrastructure.kafka.RatingAction;
import com.feelm.backend.infrastructure.kafka.RatingEventPayload;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

@DisplayName("평가 생성 서비스 규칙")
class RatingCreateServiceTest {

    private static final long USER_ID = 3L;
    private static final long MOVIE_ID = 5L;
    private static final Instant NOW = Instant.parse("2026-09-16T01:00:00Z");

    private RatingCommandRepository repository;
    private FrameWriter frameWriter;
    private TasteAnalysisWriter tasteAnalysisWriter;
    private KafkaEventPublisher eventPublisher;
    private RatingCreateService service;

    @BeforeEach
    void setUp() {
        repository = mock(RatingCommandRepository.class);
        frameWriter = mock(FrameWriter.class);
        tasteAnalysisWriter = mock(TasteAnalysisWriter.class);
        eventPublisher = mock(KafkaEventPublisher.class);
        service =
                new RatingCreateService(
                        repository,
                        new RatingStateWriter(
                                repository, frameWriter, mock(NotificationCommandService.class)),
                        tasteAnalysisWriter,
                        eventPublisher,
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("평점·감상·집계·프레임 저장 후 RATING/CREATE 이벤트를 마지막에 발행한다")
    void createsAllStateBeforePublishingEvent() {
        RatingCreateRequest request =
                new RatingCreateRequest(
                        new BigDecimal("4.5"), "좋아요", RatingVisibility.PUBLIC, false);
        when(repository.lockActiveUser(USER_ID)).thenReturn(true);
        when(repository.lockMovie(MOVIE_ID))
                .thenReturn(Optional.of(new LockedMovie(MOVIE_ID, 7L, "FLAVOR_TEST")));
        when(repository.findActiveWatchForUpdate(USER_ID, MOVIE_ID)).thenReturn(Optional.empty());
        when(repository.createRatedWatch(USER_ID, MOVIE_ID, NOW)).thenReturn(11L);
        when(repository.createRating(
                        USER_ID,
                        MOVIE_ID,
                        11L,
                        request.score(),
                        request.comment(),
                        request.visibility(),
                        false,
                        NOW))
                .thenReturn(new CreatedRating(13L, NOW));
        when(frameWriter.create(13L, USER_ID, MOVIE_ID, NOW))
                .thenReturn(new FrameCreation(17L, NOW));
        when(repository.countUserRatingsForFlavor(USER_ID, 7L)).thenReturn(1L);

        RatingCreateResponse response = service.create(USER_ID, MOVIE_ID, request);

        assertThat(response.ratingId()).isEqualTo(13L);
        assertThat(response.frame().frameId()).isEqualTo(17L);
        assertThat(response.popcorn().isNewFlavor()).isTrue();
        assertThat(response.popcorn().flavorCount()).isEqualTo(1L);
        InOrder order = inOrder(repository, frameWriter, tasteAnalysisWriter, eventPublisher);
        order.verify(repository).lockActiveUser(USER_ID);
        order.verify(repository).lockMovie(MOVIE_ID);
        order.verify(repository).ratingExists(USER_ID, MOVIE_ID);
        order.verify(repository).createRatedWatch(USER_ID, MOVIE_ID, NOW);
        order.verify(repository)
                .createRating(
                        USER_ID,
                        MOVIE_ID,
                        11L,
                        request.score(),
                        request.comment(),
                        request.visibility(),
                        false,
                        NOW);
        order.verify(repository).refreshMovieRatingAggregate(MOVIE_ID);
        order.verify(frameWriter).create(13L, USER_ID, MOVIE_ID, NOW);
        order.verify(repository).countUserRatingsForFlavor(USER_ID, 7L);
        // 취향 분석 캐시는 평가 저장 뒤 같은 트랜잭션에서 한 번 다시 계산된다(S15P21E106-775).
        order.verify(tasteAnalysisWriter).refresh(USER_ID, NOW);
        order.verify(eventPublisher)
                .publish(
                        KafkaEventType.RATING,
                        USER_ID,
                        new RatingEventPayload(RatingAction.CREATE, MOVIE_ID, request.score()));
    }

    @Test
    @DisplayName("중복 평가는 도메인 쓰기와 이벤트 발행 전에 409 오류로 거절한다")
    void duplicateStopsBeforeWritesAndEvent() {
        when(repository.lockActiveUser(USER_ID)).thenReturn(true);
        when(repository.lockMovie(MOVIE_ID))
                .thenReturn(Optional.of(new LockedMovie(MOVIE_ID, null, null)));
        when(repository.ratingExists(USER_ID, MOVIE_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.create(USER_ID, MOVIE_ID, request()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.RATING_ALREADY_EXISTS));

        verify(repository, never()).createRatedWatch(USER_ID, MOVIE_ID, NOW);
        verifyNoInteractions(frameWriter, tasteAnalysisWriter, eventPublisher);
    }

    @Test
    @DisplayName("비활성 사용자는 영화 조회와 쓰기 전에 인증 오류로 거절한다")
    void inactiveUserStopsBeforeMovieAndWrites() {
        when(repository.lockActiveUser(USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.create(USER_ID, MOVIE_ID, request()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.UNAUTHORIZED));

        verify(repository, never()).lockMovie(MOVIE_ID);
        verifyNoInteractions(frameWriter, tasteAnalysisWriter, eventPublisher);
    }

    @Test
    @DisplayName("스포일러 값을 저장소와 응답에 전달하고 기존 쓰기 순서를 지킨다")
    void passesSpoilerThroughExistingWriteOrder() {
        RatingCreateRequest request =
                new RatingCreateRequest(new BigDecimal("4.5"), "반전", RatingVisibility.PUBLIC, true);
        when(repository.lockActiveUser(USER_ID)).thenReturn(true);
        when(repository.lockMovie(MOVIE_ID))
                .thenReturn(Optional.of(new LockedMovie(MOVIE_ID, 7L, "FLAVOR_TEST")));
        when(repository.createRatedWatch(USER_ID, MOVIE_ID, NOW)).thenReturn(11L);
        when(repository.createRating(
                        USER_ID,
                        MOVIE_ID,
                        11L,
                        request.score(),
                        request.comment(),
                        request.visibility(),
                        true,
                        NOW))
                .thenReturn(new CreatedRating(13L, NOW));
        when(frameWriter.create(13L, USER_ID, MOVIE_ID, NOW))
                .thenReturn(new FrameCreation(17L, NOW));

        RatingCreateResponse response = service.create(USER_ID, MOVIE_ID, request);

        assertThat(response.spoiler()).isTrue();
        InOrder order = inOrder(repository, frameWriter, tasteAnalysisWriter, eventPublisher);
        order.verify(repository)
                .createRating(
                        USER_ID,
                        MOVIE_ID,
                        11L,
                        request.score(),
                        request.comment(),
                        request.visibility(),
                        true,
                        NOW);
        order.verify(repository).refreshMovieRatingAggregate(MOVIE_ID);
        order.verify(frameWriter).create(13L, USER_ID, MOVIE_ID, NOW);
        order.verify(eventPublisher)
                .publish(
                        KafkaEventType.RATING,
                        USER_ID,
                        new RatingEventPayload(RatingAction.CREATE, MOVIE_ID, request.score()));
    }

    private static RatingCreateRequest request() {
        return new RatingCreateRequest(
                new BigDecimal("4.5"), null, RatingVisibility.PRIVATE, false);
    }
}
