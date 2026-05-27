package com.att.tdp.issueflow.audit;

import com.att.tdp.issueflow.auth.AuthenticatedUser;
import com.att.tdp.issueflow.common.audit.AuditService;
import com.att.tdp.issueflow.common.domain.AuditAction;
import com.att.tdp.issueflow.common.domain.AuditActor;
import com.att.tdp.issueflow.common.domain.EntityType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Primary
@Service
@RequiredArgsConstructor
public class AuditLogService implements AuditService {

    private final AuditLogRepository repository;

    @Override
    @Transactional
    public void log(AuditAction action, EntityType entityType, Long entityId) {
        AuditLog row = baseRow(action, entityType, entityId);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof AuthenticatedUser principal) {
            row.setActor(AuditActor.USER);
            row.setPerformedBy(principal.userId());
        } else {
            row.setActor(AuditActor.SYSTEM);
            row.setPerformedBy(null);
        }
        repository.save(row);
    }

    @Override
    @Transactional
    public void logSystem(AuditAction action, EntityType entityType, Long entityId) {
        AuditLog row = baseRow(action, entityType, entityId);
        row.setActor(AuditActor.SYSTEM);
        row.setPerformedBy(null);
        repository.save(row);
    }

    private AuditLog baseRow(AuditAction action, EntityType entityType, Long entityId) {
        AuditLog row = new AuditLog();
        row.setAction(action);
        row.setEntityType(entityType);
        row.setEntityId(entityId);
        row.setTimestamp(Instant.now());
        return row;
    }
}
