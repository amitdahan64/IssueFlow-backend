package com.att.tdp.issueflow.audit;

import com.att.tdp.issueflow.common.domain.AuditAction;
import com.att.tdp.issueflow.common.domain.AuditActor;
import com.att.tdp.issueflow.common.domain.EntityType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "ix_audit_entity", columnList = "entity_type,entity_id"),
        @Index(name = "ix_audit_action", columnList = "action"),
        @Index(name = "ix_audit_actor", columnList = "actor")
})
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 32)
    private EntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "performed_by")
    private Long performedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AuditActor actor;

    @Column(nullable = false)
    private Instant timestamp;
}
