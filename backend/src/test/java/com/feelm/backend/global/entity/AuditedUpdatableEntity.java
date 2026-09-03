package com.feelm.backend.global.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** BaseUpdatableEntity(createdAt + updatedAt) 검증용 테스트 전용 엔티티. */
@Entity
class AuditedUpdatableEntity extends BaseUpdatableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    protected AuditedUpdatableEntity() {}

    AuditedUpdatableEntity(String name) {
        this.name = name;
    }

    Long getId() {
        return id;
    }

    void rename(String name) {
        this.name = name;
    }
}
