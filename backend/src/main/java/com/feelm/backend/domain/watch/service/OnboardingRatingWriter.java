package com.feelm.backend.domain.watch.service;

import com.feelm.backend.domain.watch.repository.RatingCommandRepository;
import com.feelm.backend.domain.watch.repository.RatingCommandRepository.LockedMovie;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 온보딩 선택을 기존 평가 저장 규칙으로 변환한다. */
@Service
@RequiredArgsConstructor
public class OnboardingRatingWriter {

    private final RatingCommandRepository repository;
    private final RatingStateWriter ratingStateWriter;
    private final Clock clock;

    /** 모든 영화 행을 결정적 순서로 잠근 뒤 아직 평가하지 않은 영화만 평가로 만든다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int createMissing(long userId, List<RatingChoice> choices) {
        List<RatingChoice> sorted =
                choices.stream().sorted(Comparator.comparingLong(RatingChoice::movieId)).toList();
        List<LockedChoice> lockedChoices = new ArrayList<>(sorted.size());
        for (RatingChoice choice : sorted) {
            LockedMovie movie =
                    repository
                            .lockMovie(choice.movieId())
                            .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
            lockedChoices.add(new LockedChoice(movie, choice.score()));
        }

        Instant createdAt = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        int createdCount = 0;
        for (LockedChoice choice : lockedChoices) {
            if (repository.ratingExists(userId, choice.movie().movieId())) {
                continue;
            }
            ratingStateWriter.create(
                    userId, choice.movie(), choice.score(), null, null, false, createdAt);
            createdCount++;
        }
        return createdCount;
    }

    public record RatingChoice(long movieId, BigDecimal score) {}

    private record LockedChoice(LockedMovie movie, BigDecimal score) {}
}
