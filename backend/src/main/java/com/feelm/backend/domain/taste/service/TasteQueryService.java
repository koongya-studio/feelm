package com.feelm.backend.domain.taste.service;

import com.feelm.backend.domain.taste.dto.response.PopcornBucketResponse;
import com.feelm.backend.domain.taste.dto.response.PopcornFlavorListResponse;
import com.feelm.backend.domain.taste.entity.PopcornFlavor;
import com.feelm.backend.domain.taste.repository.PopcornBucketQueryRepository;
import com.feelm.backend.domain.taste.repository.PopcornBucketQueryRepository.PopcornFlavorAggregate;
import com.feelm.backend.domain.taste.repository.PopcornFlavorRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TasteQueryService {

    private final PopcornFlavorRepository popcornFlavorRepository;
    private final PopcornBucketQueryRepository popcornBucketQueryRepository;

    public TasteQueryService(
            PopcornFlavorRepository popcornFlavorRepository,
            PopcornBucketQueryRepository popcornBucketQueryRepository) {
        this.popcornFlavorRepository = popcornFlavorRepository;
        this.popcornBucketQueryRepository = popcornBucketQueryRepository;
    }

    public PopcornBucketResponse getPopcornBucket(long userId) {
        Map<Long, PopcornFlavorAggregate> aggregates =
                popcornBucketQueryRepository.findRatedMoviesByFlavor(userId).stream()
                        .collect(
                                Collectors.toMap(
                                        PopcornFlavorAggregate::flavorId, Function.identity()));
        long totalCount =
                aggregates.values().stream().mapToLong(PopcornFlavorAggregate::popcornCount).sum();
        List<PopcornBucketResponse.FlavorBucket> flavors =
                popcornFlavorRepository.findAllByOrderBySortOrderAsc().stream()
                        .map(flavor -> toFlavorBucket(flavor, aggregates.get(flavor.getId())))
                        .toList();
        return new PopcornBucketResponse(totalCount, flavors);
    }

    public PopcornFlavorListResponse getPopcornFlavors() {
        List<PopcornFlavorListResponse.Item> items =
                popcornFlavorRepository.findAllByOrderBySortOrderAsc().stream()
                        .map(PopcornFlavorListResponse.Item::from)
                        .toList();
        return new PopcornFlavorListResponse(items);
    }

    private PopcornBucketResponse.FlavorBucket toFlavorBucket(
            PopcornFlavor flavor, PopcornFlavorAggregate aggregate) {
        // 집계 결과가 없는 기준 맛도 화면에 필요하므로 0개인 버킷으로 채운다.
        long count = aggregate == null ? 0 : aggregate.popcornCount();
        return new PopcornBucketResponse.FlavorBucket(
                flavor.getCode(),
                flavor.getDisplayName(),
                flavor.getColor(),
                count,
                aggregate == null ? null : aggregate.averageScore(),
                aggregate == null ? null : aggregate.discoveredAt());
    }
}
