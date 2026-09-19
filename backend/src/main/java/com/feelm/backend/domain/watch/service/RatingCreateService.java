package com.feelm.backend.domain.watch.service;

import com.feelm.backend.domain.watch.dto.request.RatingCreateRequest;
import com.feelm.backend.domain.watch.dto.response.RatingCreateResponse;
import com.feelm.backend.domain.watch.dto.response.RatingCreateResponse.Frame;
import com.feelm.backend.domain.watch.dto.response.RatingCreateResponse.Popcorn;
import com.feelm.backend.domain.watch.repository.RatingCommandRepository;
import com.feelm.backend.domain.watch.repository.RatingCommandRepository.LockedMovie;
import com.feelm.backend.domain.watch.service.RatingStateWriter.CreatedRatingState;
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
    private final RatingStateWriter ratingStateWriter;
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
        CreatedRatingState created =
                ratingStateWriter.create(
                        userId,
                        movie,
                        request.score(),
                        request.comment(),
                        request.visibility(),
                        request.spoiler(),
                        now);
        eventPublisher.publish(
                KafkaEventType.RATING,
                userId,
                new RatingEventPayload(RatingAction.CREATE, movieId, request.score()));

        return new RatingCreateResponse(
                created.rating().ratingId(),
                movieId,
                request.score(),
                request.comment(),
                request.visibility(),
                request.spoiler(),
                new Frame(created.frame().frameId(), created.frame().createdAt()),
                new Popcorn(movie.flavorCode(), created.flavorCount() == 1, created.flavorCount()),
                com.feelm.backend.domain.watch.model.WatchStatus.RATED,
                created.rating().createdAt());
    }
}
