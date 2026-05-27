package com.att.tdp.issueflow.common.audit;

import com.att.tdp.issueflow.common.domain.AuditAction;
import com.att.tdp.issueflow.common.domain.EntityType;

public interface AuditService {

    /**
     * Records a user-initiated state change. The performer is resolved from the
     * current SecurityContext by the production implementation (Phase 3).
     */
    void log(AuditAction action, EntityType entityType, Long entityId);

    /**
     * Records a system-initiated state change (scheduler, auto-assign, etc).
     */
    void logSystem(AuditAction action, EntityType entityType, Long entityId);
}