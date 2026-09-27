package com.feelm.backend.domain.recommendation.service;

import com.feelm.backend.domain.recommendation.dto.response.DismissalListItemResponse;
import com.feelm.backend.domain.recommendation.dto.response.DismissalListResponse;
import com.feelm.backend.domain.recommendation.repository.DismissalListRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DismissalListService {
    private final DismissalListRepository repository;

    /** 활성 회원 검사·커서 행 검증·목록을 동일한 읽기 스냅샷에서 조회한다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DismissalListResponse getList(long userId, String encodedCursor, String requestedSize) {
        if (!repository.isActive(userId)) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        int size = 20;
        try {
            if (requestedSize != null) size = Integer.parseInt(requestedSize);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (size < 1 || size > 50) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        DismissalListCursor cursor = DismissalListCursor.decode(encodedCursor, userId);
        if (cursor != null && !repository.hasCursor(userId, cursor)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        List<DismissalListItemResponse> rows = repository.findPage(userId, cursor, size + 1);
        boolean hasNext = rows.size() > size;
        List<DismissalListItemResponse> items = hasNext ? rows.subList(0, size) : rows;
        String next = null;
        if (hasNext) {
            DismissalListItemResponse last = items.getLast();
            next = new DismissalListCursor(last.dismissedAt(), last.dismissalId()).encode(userId);
        }
        return new DismissalListResponse(items, next, hasNext);
    }
}
