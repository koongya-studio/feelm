package com.feelm.backend.global.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import org.springframework.data.annotation.LastModifiedDate;

@MappedSuperclass
public abstract class BaseUpdatableEntity extends BaseTimeEntity {
    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
