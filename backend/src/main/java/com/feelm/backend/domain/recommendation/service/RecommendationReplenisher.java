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

/** API-045 조회 트랜잭션에서 현재 추천 세트의 빈 슬롯을 보충한다. */
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
            ServingItem next = exposeNext(userId, setId, "TASTE");
            if (next == null) {
                break;
            }
            added.add(next);
            taste++;
        }

        while (discovery < discoveryTarget && visible.size() + added.size() < targetSize) {
            ServingItem next = exposeNext(userId, setId, "DISCOVERY");
            if (next == null) {
                break;
            }
            added.add(next);
            discovery++;
        }

        while (visible.size() + added.size() < targetSize && discovery < discoveryTarget) {
            ServingItem next = exposeNext(userId, setId, "TASTE");
            if (next == null) {
                break;
            }
            added.add(next);
        }
        return List.copyOf(added);
    }

    private long count(List<ServingItem> items, String type) {
        return items.stream().filter(item -> type.equals(item.type())).count();
    }

    private ServingItem exposeNext(long userId, long setId, String type) {
        ServingItem next = repository.findAndLockNextItem(userId, setId, type).orElse(null);
        if (next != null
                && !repository.expose(
                        userId,
                        setId,
                        List.of(next),
                        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))) {
            throw new IllegalStateException("추천 후보 노출 상태가 변경되었습니다.");
        }
        return next;
    }
}
