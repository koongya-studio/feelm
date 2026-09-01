package com.feelm.backend.global.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** BaseTimeEntity(createdAt만) 검증용 테스트 전용 엔티티. */
@Entity
class AuditedCreatedEntity extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    protected AuditedCreatedEntity() {}

    AuditedCreatedEntity(String name) {
        this.name = name;
    }

    Long getId() {
        return id;
    }
}
