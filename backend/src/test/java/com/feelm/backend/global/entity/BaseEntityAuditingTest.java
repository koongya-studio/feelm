package com.feelm.backend.global.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.global.config.JpaAuditingConfig;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import(JpaAuditingConfig.class)
@DisplayName("BaseEntity — JPA Auditing 자동 기록")
class BaseEntityAuditingTest {

    @Autowired private TestEntityManager entityManager;

    @Test
    @DisplayName("BaseTimeEntity를 저장하면 createdAt이 자동으로 채워진다")
    void createdAtIsSetOnPersist() {
        AuditedCreatedEntity entity = new AuditedCreatedEntity("frame");

        entityManager.persistAndFlush(entity);

        assertThat(entity.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("BaseUpdatableEntity를 저장하면 createdAt과 updatedAt이 함께 채워진다")
    void updatableEntityHasBothTimestampsOnPersist() {
        AuditedUpdatableEntity entity = new AuditedUpdatableEntity("popcorn");

        entityManager.persistAndFlush(entity);

        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("BaseUpdatableEntity를 수정하면 updatedAt만 갱신되고 createdAt은 유지된다")
    void updatedAtChangesOnModificationWhileCreatedAtStays() throws Exception {
        AuditedUpdatableEntity entity = new AuditedUpdatableEntity("before");
        entityManager.persistAndFlush(entity);
        Instant createdAt = entity.getCreatedAt();
        Instant firstUpdatedAt = entity.getUpdatedAt();

        Thread.sleep(10);
        entity.rename("after");
        entityManager.flush();

        assertThat(entity.getCreatedAt()).isEqualTo(createdAt);
        assertThat(entity.getUpdatedAt()).isAfter(firstUpdatedAt);
    }
}
