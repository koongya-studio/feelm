package com.feelm.backend.domain.taste.service;

import com.feelm.backend.domain.taste.dto.response.PopcornBucketResponse;
import com.feelm.backend.domain.taste.dto.response.PopcornFlavorListResponse;
import com.feelm.backend.domain.taste.entity.PopcornFlavor;
import com.feelm.backend.domain.taste.repository.PopcornBucketQueryRepository;
import com.feelm.backend.domain.taste.repository.PopcornBucketQueryRepository.PopcornFlavorAggregate;
import com.feelm.backend.domain.taste.repository.PopcornFlavorRepository;
import java.util.LinkedHashMap;
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
        return getPopcornBuckets(List.of(userId)).get(userId);
    }

    /** 여러 사용자의 팝콘 버킷을 같은 기준 맛 목록과 단일 집계 조회로 생성한다. */
    public Map<Long, PopcornBucketResponse> getPopcornBuckets(List<Long> userIds) {
        Map<Long, Map<Long, PopcornFlavorAggregate>> aggregatesByUser =
                popcornBucketQueryRepository.findRatedMoviesByFlavor(userIds).stream()
                        .collect(
                                Collectors.groupingBy(
                                        PopcornFlavorAggregate::userId,
                                        Collectors.toMap(
                                                PopcornFlavorAggregate::flavorId,
                                                Function.identity())));
        List<PopcornFlavor> flavors = popcornFlavorRepository.findAllByOrderBySortOrderAsc();
        Map<Long, PopcornBucketResponse> buckets = new LinkedHashMap<>();
        userIds.forEach(
                userId ->
                        buckets.put(
                                userId,
                                toPopcornBucket(
                                        flavors, aggregatesByUser.getOrDefault(userId, Map.of()))));
        return buckets;
    }

    private PopcornBucketResponse toPopcornBucket(
            List<PopcornFlavor> flavors, Map<Long, PopcornFlavorAggregate> aggregates) {
        long totalCount =
                aggregates.values().stream().mapToLong(PopcornFlavorAggregate::popcornCount).sum();
        List<PopcornBucketResponse.FlavorBucket> buckets =
                flavors.stream()
                        .map(flavor -> toFlavorBucket(flavor, aggregates.get(flavor.getId())))
                        .toList();
        return new PopcornBucketResponse(totalCount, buckets);
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
