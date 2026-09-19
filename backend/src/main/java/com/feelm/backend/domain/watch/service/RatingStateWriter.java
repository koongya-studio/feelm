package com.feelm.backend.domain.watch.service;

import com.feelm.backend.domain.taste.service.FrameWriter;
import com.feelm.backend.domain.taste.service.FrameWriter.FrameCreation;
import com.feelm.backend.domain.watch.model.RatingVisibility;
import com.feelm.backend.domain.watch.repository.RatingCommandRepository;
import com.feelm.backend.domain.watch.repository.RatingCommandRepository.ActiveWatchRecord;
import com.feelm.backend.domain.watch.repository.RatingCommandRepository.CreatedRating;
import com.feelm.backend.domain.watch.repository.RatingCommandRepository.LockedMovie;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** API-036과 온보딩 평가가 공유하는 평가·감상·집계·프레임 저장 규칙. */
@Service
@RequiredArgsConstructor
public class RatingStateWriter {

    private final RatingCommandRepository repository;
    private final FrameWriter frameWriter;

    /** 호출자가 사용자·영화 잠금과 기존 평가 판정을 마친 트랜잭션에서 새 평가 상태를 만든다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public CreatedRatingState create(
            long userId,
            LockedMovie movie,
            BigDecimal score,
            String comment,
            RatingVisibility visibility,
            boolean spoiler,
            Instant createdAt) {
        long watchRecordId = ratedWatchRecord(userId, movie.movieId(), createdAt);
        CreatedRating rating =
                repository.createRating(
                        userId,
                        movie.movieId(),
                        watchRecordId,
                        score,
                        comment,
                        visibility,
                        spoiler,
                        createdAt);
        repository.refreshMovieRatingAggregate(movie.movieId());
        FrameCreation frame =
                frameWriter.create(rating.ratingId(), userId, movie.movieId(), rating.createdAt());
        long flavorCount =
                movie.flavorId() == null
                        ? 0
                        : repository.countUserRatingsForFlavor(userId, movie.flavorId());
        return new CreatedRatingState(rating, frame, flavorCount);
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

    public record CreatedRatingState(CreatedRating rating, FrameCreation frame, long flavorCount) {}
}
