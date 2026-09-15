package com.feelm.backend.domain.user.repository;

import com.feelm.backend.domain.user.entity.UserBlock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserBlockRepository extends JpaRepository<UserBlock, Long> {

    /** 내가 이미 차단한 상대인지. 중복 차단(API-020 {@code ALREADY_BLOCKED})을 미리 거른다. */
    boolean existsByBlockerIdAndBlockedId(long blockerId, long blockedId);

    /**
     * 차단 관계를 지운다(API-021).
     *
     * <p>해제는 완전 멱등이라 관계가 없어도 오류로 보지 않으므로 호출자는 반환값을 보지 않아도 된다. 영속성 컨텍스트에 남은 이전 상태가 뒤이은 조회에 보이지 않도록
     * 실행 전후로 flush·clear 한다.
     *
     * @return 지운 행 수. 0이면 차단 관계가 없었다는 뜻이다
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserBlock b where b.blocker.id = :blockerId and b.blocked.id = :blockedId")
    int deleteByBlockerIdAndBlockedId(
            @Param("blockerId") long blockerId, @Param("blockedId") long blockedId);

    /**
     * 탈퇴(API-013)가 이 사용자가 걸린 차단 관계를 양방향으로 지운다(플랜 결정 U2).
     *
     * <p>상대가 건 차단까지 지우는 이유는 계정이 사라졌는데 상대의 차단 목록(API-022)에 죽은 항목이 남을 이유가 없기 때문이다. 재가입은 새 {@code
     * users} 행이라 옛 차단이 이어지지도 않는다.
     *
     * <p>{@link #deleteByBlockerIdAndBlockedId}와 같은 이유로 실행 전후에 flush·clear 한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserBlock b where b.blocker.id = :userId or b.blocked.id = :userId")
    int deleteAllByUserId(@Param("userId") long userId);

    /**
     * 차단 목록 첫 페이지(API-022). 최신순이며 같은 시각은 id 역순으로 안정 정렬한다.
     *
     * <p>목록이 닉네임을 함께 반환하므로 대상 사용자를 fetch join 해 N+1을 피한다(컨벤션 7장).
     *
     * <p>{@code blocker_id}는 UK 선두 컬럼이라 조회는 인덱스를 타지만 정렬 키를 덮는 인덱스는 없어 정렬 비용이 남는다. 사용자당 차단 건수가 작다는
     * 전제이며, 정렬 인덱스 추가는 차단 건수 상한과 함께 후속에서 판단한다.
     */
    @Query(
            "select b from UserBlock b join fetch b.blocked"
                    + " where b.blocker.id = :blockerId"
                    + " order by b.createdAt desc, b.id desc")
    List<UserBlock> findPage(@Param("blockerId") long blockerId, Limit limit);

    /** 차단 목록 다음 페이지(API-022). 커서가 가리키는 {@code (createdAt, id)}보다 뒤에 오는 행만 읽는다. */
    @Query(
            "select b from UserBlock b join fetch b.blocked"
                    + " where b.blocker.id = :blockerId"
                    + " and (b.createdAt < :beforeTime"
                    + " or (b.createdAt = :beforeTime and b.id < :beforeId))"
                    + " order by b.createdAt desc, b.id desc")
    List<UserBlock> findPageBefore(
            @Param("blockerId") long blockerId,
            @Param("beforeTime") Instant beforeTime,
            @Param("beforeId") long beforeId,
            Limit limit);

    /**
     * 후보 중 나와 양방향 차단 관계인 사용자 id. 초대 후보·검색 결과에서 제외할 대상을 한 번에 가려낸다.
     *
     * <p>후보를 {@code in} 절에 그대로 바인딩하므로 호출자가 후보 수를 제한한다. 권장 상한은 1,000건이며 그 이상은 나눠 호출한다(컨벤션 11장).
     */
    @Query(
            "select case when b.blocker.id = :userId then b.blocked.id else b.blocker.id end"
                    + " from UserBlock b"
                    + " where (b.blocker.id = :userId and b.blocked.id in :candidateIds)"
                    + " or (b.blocked.id = :userId and b.blocker.id in :candidateIds)")
    List<Long> findCounterpartIdsAmong(
            @Param("userId") long userId, @Param("candidateIds") Collection<Long> candidateIds);

    /** 나와 양방향 차단 관계인 모든 사용자 id. 후보를 미리 좁힐 수 없는 목록 조회가 쓴다. */
    @Query(
            "select case when b.blocker.id = :userId then b.blocked.id else b.blocker.id end"
                    + " from UserBlock b"
                    + " where b.blocker.id = :userId or b.blocked.id = :userId")
    List<Long> findAllCounterpartIds(@Param("userId") long userId);
}
