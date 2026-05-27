package com.att.tdp.issueflow.audit.dto;

import com.att.tdp.issueflow.audit.AuditLog;
import com.att.tdp.issueflow.common.domain.AuditAction;
import com.att.tdp.issueflow.common.domain.AuditActor;
import com.att.tdp.issueflow.common.domain.EntityType;

import java.time.Instant;

public record AuditLogResponseDto(
        Long id,
        AuditAction action,
        EntityType entityType,
        Long entityId,
        Long performedBy,
        AuditActor actor,
        Instant timestamp
) {
    public static AuditLogResponseDto from(AuditLog row) {
        return new AuditLogResponseDto(
                row.getId(),
                row.getAction(),
                row.getEntityType(),
                row.getEntityId(),
                row.getPerformedBy(),
                row.getActor(),
                row.getTimestamp()
        );
    }
}
