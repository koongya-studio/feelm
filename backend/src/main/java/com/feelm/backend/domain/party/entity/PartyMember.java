package com.feelm.backend.domain.party.entity;

import com.feelm.backend.domain.user.entity.User;
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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "party_members",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_party_members_party_user",
                        columnNames = {"party_id", "user_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PartyMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "party_id", nullable = false)
    private Party party;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Role role;

    @Column(nullable = false, updatable = false)
    private Instant joinedAt;

    public static PartyMember ownerOf(Party party) {
        PartyMember member = new PartyMember();
        member.party = party;
        member.user = party.getOwner();
        member.role = Role.OWNER;
        member.joinedAt = party.getCreatedAt();
        return member;
    }

    public static PartyMember memberOf(Party party, User user, Instant joinedAt) {
        if (party == null || user == null || joinedAt == null) {
            throw new IllegalArgumentException(
                    "Party member requires a party, user, and join time");
        }
        PartyMember member = new PartyMember();
        member.party = party;
        member.user = user;
        member.role = Role.MEMBER;
        member.joinedAt = joinedAt;
        return member;
    }

    public enum Role {
        OWNER,
        MEMBER
    }
}
