package com.feelm.backend.domain.party.repository;

import com.feelm.backend.domain.party.entity.Party;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PartyRepository extends JpaRepository<Party, Long> {
    /**
     * 파티 행을 비관적 쓰기 잠금으로 조회한다.
     *
     * <p>탈퇴와 파티 종료 처리에서 먼저 파티 단위 잠금을 획득한 뒤, 멤버 여부와 현재 오너를 별도로 확인하기 위해 사용한다. 같은 파티를 변경하는 동시 요청은 현재
     * 트랜잭션이 끝날 때까지 대기한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Party p where p.id = :partyId")
    Optional<Party> findByIdForUpdate(@Param("partyId") long partyId);

    /**
     * 요청자가 멤버인 파티를 오너와 함께 조회하고 파티 행에 비관적 쓰기 잠금을 건다.
     *
     * <p>{@code join fetch p.owner}는 권한 확인에 사용할 현재 오너를 함께 조회하고, {@code exists} 서브쿼리는 {@code
     * PartyMember}를 결과에 조인하지 않고 요청자의 멤버십 존재 여부만 확인한다. 기존 멤버 강퇴 처리에서 요청자의 접근 가능 여부 확인과 동시 변경 직렬화를 위해
     * 사용한다.
     *
     * @return 파티가 존재하고 요청자가 현재 멤버이면 잠긴 파티, 아니면 빈 값
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select p from Party p join fetch p.owner"
                    + " where p.id = :partyId"
                    + " and exists (select m.id from PartyMember m"
                    + " where m.party = p and m.user.id = :requesterId)")
    Optional<Party> findAccessibleByIdForUpdate(
            @Param("partyId") long partyId, @Param("requesterId") long requesterId);
}
