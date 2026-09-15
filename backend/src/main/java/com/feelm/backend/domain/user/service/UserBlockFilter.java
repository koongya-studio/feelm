package com.feelm.backend.domain.user.service;

import com.feelm.backend.domain.user.repository.UserBlockRepository;
import java.util.Collection;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 다른 도메인이 쓰는 차단 필터(FR-39). 사용자 도메인이 소유하고 한줄평·파티 초대가 호출한다({@code backend-role-assignment.md} 도메인 간
 * 경계 규칙).
 *
 * <p>서블릿 {@code Filter}가 아니라 조회 결과에서 차단 상대를 걸러내기 위한 읽기 전용 컴포넌트다. 차단은 단방향 행으로 저장하지만 노출 차단은 양방향이므로 이
 * 컴포넌트의 모든 판정은 두 방향을 함께 본다. 차단 관계를 만들거나 지우는 일은 {@link UserBlockService}에만 있다.
 *
 * <p>쓰는 쪽 기준으로 셋 중 하나를 고른다.
 *
 * <ul>
 *   <li>상대가 한 명이면 {@link #isBlocked(long, long)} — 프로필·상세 조회, 초대 대상 검사
 *   <li>후보 집합이 이미 정해졌으면 {@link #blockedAmong(long, Collection)} — 파티원 목록, 초대 후보
 *   <li>후보를 미리 좁힐 수 없으면 {@link #blockedCounterpartIds(long)} — 한줄평 목록처럼 조회한 뒤 제외해야 하는 경우
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserBlockFilter {

    private final UserBlockRepository blockRepository;

    /**
     * 두 사용자가 어느 방향으로든 차단 관계인지.
     *
     * @return 같은 사용자면 {@code false}
     */
    public boolean isBlocked(long userId, long otherUserId) {
        if (userId == otherUserId) {
            return false;
        }
        // 두 방향을 각각 UK 인덱스로 확인한다. 한 방향에서 찾으면 두 번째 조회는 하지 않는다.
        return blockRepository.existsByBlockerIdAndBlockedId(userId, otherUserId)
                || blockRepository.existsByBlockerIdAndBlockedId(otherUserId, userId);
    }

    /**
     * 후보 중 차단 관계인 사용자 id만 골라낸다. 호출자는 반환된 id를 후보에서 빼면 된다.
     *
     * <p>후보는 {@code in} 절에 그대로 들어가므로 한 번에 1,000건 이하로 나눠 호출한다. 그보다 크면 JDBC 바인드 파라미터 한계와 실행 계획 악화에
     * 걸린다.
     *
     * @return 후보가 비어 있으면 빈 Set. 자기 자신은 후보에 있어도 포함하지 않는다
     */
    public Set<Long> blockedAmong(long userId, Collection<Long> candidateUserIds) {
        if (candidateUserIds == null || candidateUserIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(blockRepository.findCounterpartIdsAmong(userId, candidateUserIds));
    }

    /**
     * 나와 차단 관계인 모든 사용자 id.
     *
     * <p>결과 크기가 차단 건수에 비례하므로 후보를 미리 좁힐 수 있으면 {@link #blockedAmong(long, Collection)}을 쓴다.
     */
    public Set<Long> blockedCounterpartIds(long userId) {
        return Set.copyOf(blockRepository.findAllCounterpartIds(userId));
    }
}
