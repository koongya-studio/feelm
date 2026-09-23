package com.feelm.backend.domain.recommendation.service;

import com.feelm.backend.domain.recommendation.repository.RecommendationServingRepository;
import com.feelm.backend.domain.recommendation.repository.RecommendationServingRepository.ServingItem;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * API-045 조회 트랜잭션에서 현재 추천 세트의 빈 슬롯을 보충한다.
 *
 * <p>빈 슬롯마다 {@code recommendation_items} 행은 하나씩 순차로 잠그고 노출 상태로 표시한다(다음 슬롯 탐색이 방금 노출한 행을 걸러야 하므로).
 * 그러나 {@code user_movie_exposures} 이력 기록은 movies FK 검사로 movies 행을 잠그므로, 슬롯마다 나눠 기록하면 그 사이 순서가 온보딩의
 * movies 잠금 순서(id 오름차순)와 어긋나 교착할 수 있다(S15P21E106-804 리뷰 B-F6). 그래서 items 노출은 순차로 하되 이력 기록은 이번 요청에서
 * 새로 노출한 항목을 다 모은 뒤 한 번만, movie_id 오름차순으로 한다.
 */
@Component
@RequiredArgsConstructor
public class RecommendationReplenisher {

    private final RecommendationServingRepository repository;
    private final Clock clock;

    /** 활성 트랜잭션 안에서 현재 세트의 빈 슬롯만 보충한다. 호출자가 사용자 단위 직렬화를 보장한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ServingItem> replenishVacancies(
            long userId,
            long setId,
            List<ServingItem> visible,
            int tasteTarget,
            int discoveryTarget) {
        List<ServingItem> added = new ArrayList<>();
        long taste = count(visible, "TASTE");
        long discovery = count(visible, "DISCOVERY");
        int targetSize = tasteTarget + discoveryTarget;

        while (taste < tasteTarget && visible.size() + added.size() < targetSize) {
            ServingItem next = lockAndMarkExposed(userId, setId, "TASTE");
            if (next == null) {
                break;
            }
            added.add(next);
            taste++;
        }

        while (discovery < discoveryTarget && visible.size() + added.size() < targetSize) {
            ServingItem next = lockAndMarkExposed(userId, setId, "DISCOVERY");
            if (next == null) {
                break;
            }
            added.add(next);
            discovery++;
        }

        while (visible.size() + added.size() < targetSize && discovery < discoveryTarget) {
            ServingItem next = lockAndMarkExposed(userId, setId, "TASTE");
            if (next == null) {
                break;
            }
            added.add(next);
        }

        // 이 트랜잭션에서 새로 노출한 항목을 한 번에, movie_id 오름차순으로 이력에 남긴다.
        if (!added.isEmpty()) {
            repository.recordExposureHistory(
                    userId,
                    setId,
                    added,
                    OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC));
        }
        return List.copyOf(added);
    }

    private long count(List<ServingItem> items, String type) {
        return items.stream().filter(item -> type.equals(item.type())).count();
    }

    /**
     * 다음 후보를 잠그고 {@code recommendation_items.exposed_at}만 찍는다. movies를 잠그지 않으므로 순서를 movie_id에 맞출
     * 필요가 없다 — 이력 기록은 {@link #replenishVacancies}가 끝에서 한 번에 한다.
     */
    private ServingItem lockAndMarkExposed(long userId, long setId, String type) {
        ServingItem next = repository.findAndLockNextItem(userId, setId, type).orElse(null);
        if (next != null
                && !repository.markExposed(
                        List.of(next),
                        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))) {
            throw new IllegalStateException("추천 후보 노출 상태가 변경되었습니다.");
        }
        return next;
    }
}
