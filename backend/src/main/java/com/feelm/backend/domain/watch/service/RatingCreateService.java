package com.feelm.backend.domain.watch.service;

import com.feelm.backend.domain.taste.service.FrameWriter;
import com.feelm.backend.domain.taste.service.FrameWriter.FrameCreation;
import com.feelm.backend.domain.watch.dto.request.RatingCreateRequest;
import com.feelm.backend.domain.watch.dto.response.RatingCreateResponse;
import com.feelm.backend.domain.watch.dto.response.RatingCreateResponse.Frame;
import com.feelm.backend.domain.watch.dto.response.RatingCreateResponse.Popcorn;
import com.feelm.backend.domain.watch.repository.RatingCommandRepository;
import com.feelm.backend.domain.watch.repository.RatingCommandRepository.ActiveWatchRecord;
import com.feelm.backend.domain.watch.repository.RatingCommandRepository.CreatedRating;
import com.feelm.backend.domain.watch.repository.RatingCommandRepository.LockedMovie;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.infrastructure.kafka.KafkaEventPublisher;
import com.feelm.backend.infrastructure.kafka.KafkaEventType;
import com.feelm.backend.infrastructure.kafka.RatingAction;
import com.feelm.backend.infrastructure.kafka.RatingEventPayload;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RatingCreateService {

    private final RatingCommandRepository repository;
    private final FrameWriter frameWriter;
    private final KafkaEventPublisher eventPublisher;
    private final Clock clock;

    @Transactional
    public RatingCreateResponse create(long userId, long movieId, RatingCreateRequest request) {
        if (!repository.lockActiveUser(userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        LockedMovie movie =
                repository
                        .lockMovie(movieId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (repository.ratingExists(userId, movieId)) {
            throw new BusinessException(ErrorCode.RATING_ALREADY_EXISTS);
        }

        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        long watchRecordId = ratedWatchRecord(userId, movieId, now);
        CreatedRating rating =
                repository.createRating(
                        userId,
                        movieId,
                        watchRecordId,
                        request.score(),
                        request.comment(),
                        request.visibility(),
                        now);
        repository.refreshMovieRatingAggregate(movieId);
        FrameCreation createdFrame =
                frameWriter.create(rating.ratingId(), userId, movieId, rating.createdAt());

        long flavorCount =
                movie.flavorId() == null
                        ? 0
                        : repository.countUserRatingsForFlavor(userId, movie.flavorId());
        eventPublisher.publish(
                KafkaEventType.RATING,
                userId,
                new RatingEventPayload(RatingAction.CREATE, movieId, request.score()));

        return new RatingCreateResponse(
                rating.ratingId(),
                movieId,
                request.score(),
                request.comment(),
                request.visibility(),
                new Frame(createdFrame.frameId(), createdFrame.createdAt()),
                new Popcorn(movie.flavorCode(), flavorCount == 1, flavorCount),
                com.feelm.backend.domain.watch.model.WatchStatus.RATED,
                rating.createdAt());
    }

    private long ratedWatchRecord(long userId, long movieId, Instant ratedAt) {
        ActiveWatchRecord current =
                repository.findActiveWatchForUpdate(userId, movieId).orElse(null);
        if (current == null) {
            return repository.createRatedWatch(userId, movieId, ratedAt);
        }
        repository.markWatchRated(current.watchRecordId(), ratedAt);
        return current.watchRecordId();
    }
}
