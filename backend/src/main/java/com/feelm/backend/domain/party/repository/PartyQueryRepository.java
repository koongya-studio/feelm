package com.feelm.backend.domain.party.repository;

import com.feelm.backend.domain.party.entity.Party;
import com.feelm.backend.domain.party.entity.PartyInvitation;
import com.feelm.backend.domain.party.entity.PartyMember;
import com.feelm.backend.domain.party.entity.PartyStatus;
import com.feelm.backend.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PartyQueryRepository {
    private final EntityManager entityManager;

    public Optional<User> findUser(long userId) {
        return Optional.ofNullable(entityManager.find(User.class, userId));
    }

    public List<Party> findParties(
            long userId, PartyStatus status, Instant beforeTime, long beforeId, int limit) {
        String query =
                "select p from Party p join fetch p.owner where p.status = :status"
                        + " and exists (select m.id from PartyMember m where m.party = p and m.user.id = :userId)";
        if (beforeTime != null) {
            query +=
                    " and (p.createdAt < :beforeTime or (p.createdAt = :beforeTime and p.id < :beforeId))";
        }
        var typedQuery =
                entityManager
                        .createQuery(query + " order by p.createdAt desc, p.id desc", Party.class)
                        .setParameter("status", status)
                        .setParameter("userId", userId)
                        .setMaxResults(limit);
        if (beforeTime != null) {
            typedQuery.setParameter("beforeTime", beforeTime).setParameter("beforeId", beforeId);
        }
        return typedQuery.getResultList();
    }

    public List<PartyMember> findMembers(List<Long> partyIds) {
        return entityManager
                .createQuery(
                        "select m from PartyMember m join fetch m.user"
                                + " where m.party.id in :partyIds order by m.joinedAt, m.id",
                        PartyMember.class)
                .setParameter("partyIds", partyIds)
                .getResultList();
    }

    public List<PartyInvitation> findPendingInvitations(List<Long> partyIds) {
        return entityManager
                .createQuery(
                        "select i from PartyInvitation i join fetch i.invitee"
                                + " where i.party.id in :partyIds and i.status = :status order by i.createdAt, i.id",
                        PartyInvitation.class)
                .setParameter("partyIds", partyIds)
                .setParameter("status", PartyInvitation.Status.PENDING)
                .getResultList();
    }

    public boolean hasBlockBetweenInviteeAndPartyMembers(long partyId, long inviteeId) {
        Number count =
                (Number)
                        entityManager
                                .createNativeQuery(
                                        "select count(*) from user_blocks b"
                                                + " join party_members m on m.party_id = :partyId"
                                                + " and ((b.blocker_id = m.user_id and b.blocked_id = :inviteeId)"
                                                + " or (b.blocker_id = :inviteeId and b.blocked_id = m.user_id))")
                                .setParameter("partyId", partyId)
                                .setParameter("inviteeId", inviteeId)
                                .getSingleResult();
        return count.longValue() > 0;
    }

    public List<PartyInvitation> findReceivedInvitations(
            long inviteeId,
            PartyInvitation.Status status,
            Instant beforeTime,
            long beforeId,
            int limit) {
        String query =
                "select i from PartyInvitation i join fetch i.party p join fetch p.owner"
                        + " where i.invitee.id = :inviteeId and i.status = :status";
        if (status == PartyInvitation.Status.PENDING) {
            query += " and p.status = :partyStatus";
        }
        if (beforeTime != null) {
            query +=
                    " and (i.createdAt < :beforeTime"
                            + " or (i.createdAt = :beforeTime and i.id < :beforeId))";
        }
        var typedQuery =
                entityManager
                        .createQuery(
                                query + " order by i.createdAt desc, i.id desc",
                                PartyInvitation.class)
                        .setParameter("inviteeId", inviteeId)
                        .setParameter("status", status)
                        .setMaxResults(limit);
        if (status == PartyInvitation.Status.PENDING) {
            typedQuery.setParameter("partyStatus", PartyStatus.ACTIVE);
        }
        if (beforeTime != null) {
            typedQuery.setParameter("beforeTime", beforeTime).setParameter("beforeId", beforeId);
        }
        return typedQuery.getResultList();
    }

    public Map<Long, Long> countMembers(List<Long> partyIds) {
        List<Object[]> rows =
                entityManager
                        .createQuery(
                                "select m.party.id, count(m.id) from PartyMember m"
                                        + " where m.party.id in :partyIds group by m.party.id",
                                Object[].class)
                        .setParameter("partyIds", partyIds)
                        .getResultList();
        Map<Long, Long> counts = new LinkedHashMap<>();
        rows.forEach(row -> counts.put((Long) row[0], (Long) row[1]));
        return counts;
    }
}
