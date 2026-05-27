package com.att.tdp.issueflow.common.audit;

import com.att.tdp.issueflow.common.domain.AuditAction;
import com.att.tdp.issueflow.common.domain.EntityType;
import org.springframework.stereotype.Service;

@Service
public class NoOpAuditService implements AuditService {

    @Override
    public void log(AuditAction action, EntityType entityType, Long entityId) {
        // intentional no-op until Phase 3 wires the real implementation
    }

    @Override
    public void logSystem(AuditAction action, EntityType entityType, Long entityId) {
        // intentional no-op until Phase 3 wires the real implementation
    }
}
