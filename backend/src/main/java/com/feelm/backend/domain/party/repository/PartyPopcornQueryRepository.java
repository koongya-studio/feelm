package com.feelm.backend.domain.party.repository;

import jakarta.persistence.EntityManager;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PartyPopcornQueryRepository {
    private final EntityManager entityManager;

    /** 요청자가 속한 활성 파티의 현재 멤버를 가입 순서대로 조회한다. */
    public List<MemberSummary> findAccessibleActivePartyMembers(long partyId, long requesterId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows =
                entityManager
                        .createNativeQuery(
                                """
                                SELECT member.user_id, users.nickname
                                FROM parties party
                                JOIN party_members member ON member.party_id = party.id
                                JOIN users ON users.id = member.user_id
                                WHERE party.id = :partyId
                                  AND party.status = 'ACTIVE'
                                  AND EXISTS (
                                      SELECT 1
                                      FROM party_members requester
                                      WHERE requester.party_id = party.id
                                        AND requester.user_id = :requesterId
                                  )
                                ORDER BY member.joined_at, member.id
                                """)
                        .setParameter("partyId", partyId)
                        .setParameter("requesterId", requesterId)
                        .getResultList();
        return rows.stream()
                .map(row -> new MemberSummary(((Number) row[0]).longValue(), (String) row[1]))
                .toList();
    }

    public record MemberSummary(long userId, String nickname) {}
}
