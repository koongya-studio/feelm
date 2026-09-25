package com.feelm.backend.domain.watch.service;

import com.feelm.backend.domain.taste.service.TasteAnalysisWriter;
import com.feelm.backend.domain.watch.dto.request.RatingUpdateRequest;
import com.feelm.backend.domain.watch.dto.response.RatingCreateResponse;
import com.feelm.backend.domain.watch.dto.response.RatingCreateResponse.Frame;
import com.feelm.backend.domain.watch.dto.response.RatingCreateResponse.Popcorn;
import com.feelm.backend.domain.watch.model.WatchStatus;
import com.feelm.backend.domain.watch.repository.RatingCommandRepository;
import com.feelm.backend.domain.watch.repository.RatingCommandRepository.ActiveWatchRecord;
import com.feelm.backend.domain.watch.repository.RatingCommandRepository.ExistingFrame;
import com.feelm.backend.domain.watch.repository.RatingCommandRepository.LockedMovie;
import com.feelm.backend.domain.watch.repository.RatingCommandRepository.LockedRating;
import com.feelm.backend.domain.watch.repository.RatingCommandRepository.OwnedRating;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.infrastructure.kafka.KafkaEventPublisher;
import com.feelm.backend.infrastructure.kafka.KafkaEventType;
import com.feelm.backend.infrastructure.kafka.RatingAction;
import com.feelm.backend.infrastructure.kafka.RatingEventPayload;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RatingMutationService {

    private final RatingCommandRepository repository;
    private final TasteAnalysisWriter tasteAnalysisWriter;
    private final KafkaEventPublisher eventPublisher;
    private final Clock clock;

    @Transactional
    public RatingCreateResponse update(long userId, long ratingId, RatingUpdateRequest request) {
        LockedTarget target = lockTarget(userId, ratingId);
        ExistingFrame frame =
                repository
                        .findFrame(ratingId, userId, target.movie().movieId())
                        .orElseThrow(() -> new IllegalStateException("평가에 연결된 프레임이 없습니다."));
        if (!sameValues(target.rating(), request)) {
            Instant updatedAt = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
            repository.updateRating(
                    ratingId,
                    userId,
                    request.score(),
                    request.comment(),
                    request.visibility(),
                    request.spoiler(),
                    updatedAt);
            repository.refreshMovieRatingAggregate(target.movie().movieId());
            // 값이 바뀐 경우에만 돈다. 값이 같으면 집계 결과도 같아 다시 계산할 이유가 없다.
            tasteAnalysisWriter.refresh(userId, updatedAt);
            eventPublisher.publish(
                    KafkaEventType.RATING,
                    userId,
                    new RatingEventPayload(
                            RatingAction.UPDATE, target.movie().movieId(), request.score()));
        }

        long flavorCount =
                target.movie().flavorId() == null
                        ? 0
                        : repository.countUserRatingsForFlavor(userId, target.movie().flavorId());

        return new RatingCreateResponse(
                ratingId,
                target.movie().movieId(),
                request.score(),
                request.comment(),
                request.visibility(),
                request.spoiler(),
                new Frame(frame.frameId(), frame.createdAt()),
                new Popcorn(target.movie().flavorCode(), flavorCount == 1, flavorCount),
                WatchStatus.RATED,
                target.rating().createdAt());
    }

    /**
     * 평가를 지우고 그 영화를 <b>안 본 것으로 되돌린다</b>(API-038, ADR-0025).
     *
     * <p>평가 삭제와 감상 취소를 나누어 고르게 하지 않는다. 점수만 고칠 때는 수정(API-037)을 쓰고 영화가 마음에 들지 않았을 뿐이면 낮은 점수를 남기므로,
     * 평가를 지우는 사람은 대체로 잘못 남겼거나 보지 않은 경우다.
     *
     * <p>감상 기록은 한 트랜잭션에서 {@code EXPIRED}로 옮긴다. 화면이 평가 삭제와 감상 취소를 이어 부르면 사이에서 끊길 때 평가만 사라지고 기록이 남는데,
     * 그 상태를 수습할 경로를 화면마다 다시 만들어야 한다.
     *
     * <p>평가에 연결된 감상 기록이 없으면 건너뛴다. 과거에 기록 없이 만들어진 평가가 그렇다(D-6).
     */
    @Transactional
    public void delete(long userId, long ratingId) {
        LockedTarget target = lockTarget(userId, ratingId);
        repository.deleteRating(ratingId, userId);
        if (target.rating().watchRecordId() != null) {
            repository.markWatchExpired(
                    target.rating().watchRecordId(),
                    userId,
                    target.movie().movieId(),
                    Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
        }
        repository.refreshMovieRatingAggregate(target.movie().movieId());
        // 마지막 평가를 지우면 캐시 행도 사라진다(API-040).
        tasteAnalysisWriter.refresh(userId, Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
        eventPublisher.publish(
                KafkaEventType.RATING,
                userId,
                new RatingEventPayload(RatingAction.DELETE, target.movie().movieId(), null));
    }

    private LockedTarget lockTarget(long userId, long ratingId) {
        if (!repository.lockActiveUser(userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        OwnedRating owned =
                repository
                        .findOwnedRating(userId, ratingId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        LockedMovie movie =
                repository
                        .lockMovie(owned.movieId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (owned.watchRecordId() != null) {
            ActiveWatchRecord watch =
                    repository
                            .findActiveWatchForUpdate(userId, owned.movieId())
                            .orElseThrow(
                                    () -> new IllegalStateException("평가에 연결된 활성 감상 기록이 없습니다."));
            if (watch.watchRecordId() != owned.watchRecordId()
                    || watch.status() != WatchStatus.RATED) {
                throw new IllegalStateException("평가와 감상 기록의 연결이 일치하지 않습니다.");
            }
        }
        LockedRating rating =
                repository
                        .lockOwnedRating(userId, ratingId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (rating.movieId() != owned.movieId()
                || !Objects.equals(rating.watchRecordId(), owned.watchRecordId())) {
            throw new IllegalStateException("평가의 영화 또는 감상 기록 연결이 변경되었습니다.");
        }
        return new LockedTarget(movie, rating);
    }

    private static boolean sameValues(LockedRating rating, RatingUpdateRequest request) {
        String visibility = request.visibility() == null ? null : request.visibility().name();
        return rating.score().compareTo(request.score()) == 0
                && Objects.equals(rating.comment(), request.comment())
                && Objects.equals(rating.visibility(), visibility)
                && rating.spoiler() == request.spoiler();
    }

    private record LockedTarget(LockedMovie movie, LockedRating rating) {}
}
