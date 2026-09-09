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

@Entity
@Table(name = "parties")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Party extends BaseTimeEntity {
    public static final int MAX_MEMBERS = 4;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PartyStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ClosedReason closedReason;

    private Instant closedAt;

    public static Party create(String name, User owner) {
        if (name == null || name.isBlank() || name.length() > 20 || owner == null) {
            throw new IllegalArgumentException(
                    "Party requires a name of 1–20 characters and an owner");
        }
        Party party = new Party();
        party.name = name;
        party.owner = owner;
        party.status = PartyStatus.ACTIVE;
        return party;
    }

    public enum ClosedReason {
        INSUFFICIENT_MEMBERS,
        OWNER_CLOSED
    }
}
