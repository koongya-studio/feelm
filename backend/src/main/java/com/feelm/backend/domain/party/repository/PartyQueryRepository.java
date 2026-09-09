package com.feelm.backend.domain.party.repository;

import com.feelm.backend.domain.party.entity.Party;
import com.feelm.backend.domain.party.entity.PartyInvitation;
import com.feelm.backend.domain.party.entity.PartyMember;
import com.feelm.backend.domain.party.entity.PartyStatus;
import com.feelm.backend.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
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
}
