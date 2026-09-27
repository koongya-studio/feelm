package com.feelm.backend.domain.party.service;

import com.feelm.backend.domain.party.dto.response.PartyPopcornResponse.FlavorBucket;
import com.feelm.backend.domain.party.dto.response.PartyPopcornResponse.MemberPopcorn;
import com.feelm.backend.domain.party.dto.response.PartyPopcornResponse.UserSummary;
import com.feelm.backend.domain.party.dto.response.PartyPopcornSnapshotResponse;
import com.feelm.backend.domain.party.dto.response.PartyResponse;
import com.feelm.backend.domain.party.repository.PartyPopcornSnapshotRepository;
import com.feelm.backend.domain.party.repository.PartyPopcornSnapshotRepository.SavedMember;
import com.feelm.backend.domain.party.repository.PartyPopcornSnapshotRepository.StoredSnapshot;
import com.feelm.backend.domain.party.repository.PartyPopcornSnapshotRepository.StoredUser;
import com.feelm.backend.domain.taste.service.TasteQueryService;
import com.feelm.backend.domain.user.service.AvatarStorage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 호출자의 파티 잠금·인가·트랜잭션 안에서 사용한다. 이름과 사진은 저장하지 않는다. */
@Component
@RequiredArgsConstructor
class PartyPopcornSnapshots {
    private final PartyPopcornSnapshotRepository repository;
    private final TasteQueryService taste;
    private final AvatarStorage avatars;

    public void capture(
            long partyId, long setId, OffsetDateTime exposedAt, List<Long> ids, String mode) {
        var buckets = taste.getPopcornBuckets(ids);
        List<SavedMember> members =
                ids.stream()
                        .map(
                                id -> {
                                    var bucket = buckets.get(id);
                                    return new SavedMember(
                                            id,
                                            bucket.totalCount(),
                                            bucket.flavors().stream()
                                                    .map(
                                                            f ->
                                                                    new FlavorBucket(
                                                                            f.flavorCode(),
                                                                            f.popcornCount(),
                                                                            f.averageScore()))
                                                    .toList());
                                })
                        .toList();
        repository.insert(partyId, setId, exposedAt, members, mode);
    }

    public PartyPopcornSnapshotResponse latest(long partyId) {
        return hydrate(repository.latest(partyId)).stream().findFirst().orElse(null);
    }

    public Map<Long, List<PartyPopcornSnapshotResponse>> forSets(long partyId, List<Long> setIds) {
        Map<Long, List<PartyPopcornSnapshotResponse>> result = new LinkedHashMap<>();
        hydrate(repository.forSets(partyId, setIds))
                .forEach(
                        snapshot ->
                                result.computeIfAbsent(
                                                snapshot.recommendationSetId(),
                                                k -> new ArrayList<>())
                                        .add(snapshot));
        return result;
    }

    public PartyPopcornSnapshotResponse current(
            long partyId, long setId, OffsetDateTime exposedAt) {
        return hydrate(repository.current(partyId, setId, exposedAt)).stream()
                .findFirst()
                .orElse(null);
    }

    private List<PartyPopcornSnapshotResponse> hydrate(List<StoredSnapshot> snapshots) {
        var ids =
                snapshots.stream()
                        .flatMap(s -> s.members().stream())
                        .map(SavedMember::userId)
                        .distinct()
                        .toList();
        var users = repository.users(ids);
        return snapshots.stream()
                .map(
                        snapshot -> {
                            var members =
                                    snapshot.members().stream()
                                            .map(
                                                    saved -> {
                                                        var user =
                                                                users.getOrDefault(
                                                                        saved.userId(),
                                                                        new StoredUser(
                                                                                PartyResponse
                                                                                        .UserSummary
                                                                                        .WITHDRAWN_NICKNAME,
                                                                                null));
                                                        return new MemberPopcorn(
                                                                new UserSummary(
                                                                        saved.userId(),
                                                                        user.nickname(),
                                                                        avatars.publicUrl(
                                                                                user
                                                                                        .avatarStorageKey())),
                                                                saved.totalCount(),
                                                                saved.flavors());
                                                    })
                                            .toList();
                            return new PartyPopcornSnapshotResponse(
                                    snapshot.setId(),
                                    snapshot.exposedAt(),
                                    snapshot.mode(),
                                    members,
                                    members.stream().mapToLong(MemberPopcorn::totalCount).sum(),
                                    mixFlavors(members));
                        })
                .toList();
    }

    private List<FlavorBucket> mixFlavors(List<MemberPopcorn> members) {
        Map<String, List<FlavorBucket>> grouped = new LinkedHashMap<>();
        members.forEach(
                member ->
                        member.flavors()
                                .forEach(
                                        flavor ->
                                                grouped.computeIfAbsent(
                                                                flavor.flavorCode(),
                                                                k -> new ArrayList<>())
                                                        .add(flavor)));
        return grouped.entrySet().stream()
                .map(
                        entry -> {
                            long count =
                                    entry.getValue().stream()
                                            .mapToLong(FlavorBucket::popcornCount)
                                            .sum();
                            BigDecimal weighted =
                                    entry.getValue().stream()
                                            .filter(f -> f.averageScore() != null)
                                            .map(
                                                    f ->
                                                            f.averageScore()
                                                                    .multiply(
                                                                            BigDecimal.valueOf(
                                                                                    f
                                                                                            .popcornCount())))
                                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                            return new FlavorBucket(
                                    entry.getKey(),
                                    count,
                                    count == 0
                                            ? null
                                            : weighted.divide(
                                                    BigDecimal.valueOf(count),
                                                    2,
                                                    RoundingMode.HALF_UP));
                        })
                .toList();
    }
}
