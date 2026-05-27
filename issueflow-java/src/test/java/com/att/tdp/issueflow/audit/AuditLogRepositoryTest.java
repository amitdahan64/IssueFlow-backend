package com.att.tdp.issueflow.audit;

import com.att.tdp.issueflow.common.config.JpaAuditingConfig;
import com.att.tdp.issueflow.common.domain.AuditAction;
import com.att.tdp.issueflow.common.domain.AuditActor;
import com.att.tdp.issueflow.common.domain.EntityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase
@Import(JpaAuditingConfig.class)
class AuditLogRepositoryTest {

    @Autowired
    private AuditLogRepository repository;

    private AuditLog row(AuditAction action, EntityType type, Long entityId, AuditActor actor, Long performedBy) {
        AuditLog r = new AuditLog();
        r.setAction(action);
        r.setEntityType(type);
        r.setEntityId(entityId);
        r.setActor(actor);
        r.setPerformedBy(performedBy);
        r.setTimestamp(Instant.now());
        return r;
    }

    @BeforeEach
    void seed() {
        repository.deleteAll();
        repository.saveAll(List.of(
                row(AuditAction.CREATE, EntityType.USER, 1L, AuditActor.SYSTEM, null),
                row(AuditAction.UPDATE, EntityType.USER, 1L, AuditActor.USER, 1L),
                row(AuditAction.DELETE, EntityType.USER, 2L, AuditActor.USER, 1L),
                row(AuditAction.LOGIN,  EntityType.AUTH, 1L, AuditActor.SYSTEM, null),
                row(AuditAction.AUTO_ASSIGN, EntityType.TICKET, 10L, AuditActor.SYSTEM, null)
        ));
    }

    @Test
    void no_filters_returns_all_rows() {
        var spec = AuditLogSpecifications.withFilters(null, null, null, null);
        assertThat(repository.findAll(spec, Sort.unsorted())).hasSize(5);
    }

    @Test
    void filters_by_entityType() {
        var spec = AuditLogSpecifications.withFilters(EntityType.USER, null, null, null);
        var results = repository.findAll(spec, Sort.unsorted());
        assertThat(results).hasSize(3);
        assertThat(results).allSatisfy(r -> assertThat(r.getEntityType()).isEqualTo(EntityType.USER));
    }

    @Test
    void filters_by_action() {
        var spec = AuditLogSpecifications.withFilters(null, null, AuditAction.CREATE, null);
        var results = repository.findAll(spec, Sort.unsorted());
        assertThat(results).singleElement().satisfies(r -> {
            assertThat(r.getEntityType()).isEqualTo(EntityType.USER);
            assertThat(r.getEntityId()).isEqualTo(1L);
        });
    }

    @Test
    void filters_by_actor() {
        var spec = AuditLogSpecifications.withFilters(null, null, null, AuditActor.SYSTEM);
        var results = repository.findAll(spec, Sort.unsorted());
        assertThat(results).hasSize(3);
        assertThat(results).allSatisfy(r -> assertThat(r.getActor()).isEqualTo(AuditActor.SYSTEM));
    }

    @Test
    void filters_combined_entityType_entityId_and_actor() {
        var spec = AuditLogSpecifications.withFilters(EntityType.USER, 1L, null, AuditActor.USER);
        var results = repository.findAll(spec, Sort.unsorted());
        assertThat(results).singleElement().satisfies(r -> {
            assertThat(r.getAction()).isEqualTo(AuditAction.UPDATE);
            assertThat(r.getPerformedBy()).isEqualTo(1L);
        });
    }
}
