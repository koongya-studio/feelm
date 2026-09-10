package com.feelm.backend.domain.party.entity;

import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 파티 초대와 초대 상태를 보관한다. */
@Entity
@Table(name = "party_invitations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PartyInvitation extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "party_id", nullable = false)
    private Party party;

    @Column(nullable = false)
    private Long inviterId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invitee_id", nullable = false)
    private User invitee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Status status;

    private Instant respondedAt;

    public static PartyInvitation create(Party party, long inviterId, User invitee) {
        if (party == null || inviterId < 1 || invitee == null || invitee.getId() == inviterId) {
            throw new IllegalArgumentException("Invitation requires a party and distinct users");
        }
        PartyInvitation invitation = new PartyInvitation();
        invitation.party = party;
        invitation.inviterId = inviterId;
        invitation.invitee = invitee;
        invitation.status = Status.PENDING;
        return invitation;
    }

    public void decline(Instant respondedAt) {
        if (status != Status.PENDING || respondedAt == null) {
            throw new IllegalStateException("Only a pending invitation can be declined");
        }
        status = Status.DECLINED;
        this.respondedAt = respondedAt;
    }

    public enum Status {
        PENDING,
        ACCEPTED,
        DECLINED
    }
}
