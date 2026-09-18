package com.feelm.backend.domain.watch.service;

import com.feelm.backend.domain.watch.cursor.MyRatingCursor;
import com.feelm.backend.domain.watch.dto.request.MyRatingCriteria;
import com.feelm.backend.domain.watch.dto.response.MyRatingItemResponse;
import com.feelm.backend.domain.watch.dto.response.MyRatingPageResponse;
import com.feelm.backend.domain.watch.repository.MyRatingQueryRepository;
import com.feelm.backend.domain.watch.repository.MyRatingQueryRepository.MyRatingRow;
import com.feelm.backend.domain.watch.repository.MyRatingQueryRepository.PageSnapshot;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MyRatingService {

    private final MyRatingQueryRepository repository;

    @Transactional(readOnly = true)
    public MyRatingPageResponse getMyRatings(long userId, MyRatingCriteria criteria) {
        MyRatingCursor cursor = null;
        BusinessException cursorFailure = null;
        try {
            cursor = MyRatingCursor.decode(criteria.cursor(), userId, criteria);
        } catch (BusinessException exception) {
            cursorFailure = exception;
        }
        PageSnapshot snapshot =
                repository.findPageSnapshot(userId, criteria, cursor, cursorFailure == null);
        if (!snapshot.viewerActive()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (cursorFailure != null) {
            throw cursorFailure;
        }

        List<MyRatingRow> results = snapshot.items();
        boolean hasNext = results.size() > criteria.size();
        List<MyRatingRow> page = hasNext ? results.subList(0, criteria.size()) : results;
        String nextCursor = null;
        if (hasNext) {
            MyRatingRow last = page.getLast();
            nextCursor =
                    new MyRatingCursor(
                                    last.activityAt(), last.score(), last.itemType(), last.itemId())
                            .encode(userId, criteria);
        }
        return new MyRatingPageResponse(
                page.stream().map(MyRatingItemResponse::from).toList(), nextCursor, hasNext);
    }
}
