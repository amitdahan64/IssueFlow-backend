package com.att.tdp.issueflow.escalation;

import com.att.tdp.issueflow.common.audit.AuditService;
import com.att.tdp.issueflow.common.domain.AuditAction;
import com.att.tdp.issueflow.common.domain.EntityType;
import com.att.tdp.issueflow.common.domain.Priority;
import com.att.tdp.issueflow.common.domain.TicketStatus;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Per-ticket escalation work. Lives in its own bean so the {@code @Retryable}
 * proxy is honored when called from {@link EscalationService} (Spring AOP
 * doesn't intercept self-calls within the same bean).
 *
 * Each invocation runs in a fresh transaction so a single failing ticket
 * doesn't roll back the whole sweep — and so the retry actually reloads from
 * the DB to see the latest {@code @Version}.
 */
@Service
@RequiredArgsConstructor
public class EscalationStep {

    private static final Logger log = LoggerFactory.getLogger(EscalationStep.class);

    private final TicketRepository ticketRepository;
    private final AuditService auditService;

    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 50, multiplier = 2)
    )
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void escalateOne(Long ticketId, Instant now) {
        Ticket t = ticketRepository.findById(ticketId).orElse(null);
        if (t == null) return;
        if (t.getStatus() == TicketStatus.DONE) return;
        if (t.getDueDate() == null || !t.getDueDate().isBefore(now)) return;

        if (t.getPriority() != Priority.CRITICAL) {
            Priority before = t.getPriority();
            t.setPriority(before.next());
            ticketRepository.saveAndFlush(t);
            auditService.logSystem(AuditAction.AUTO_ESCALATE, EntityType.TICKET, t.getId());
            log.debug("Escalated ticket {} priority {} -> {}", t.getId(), before, t.getPriority());
        } else if (!t.isOverdue()) {
            t.setOverdue(true);
            ticketRepository.saveAndFlush(t);
            auditService.logSystem(AuditAction.AUTO_ESCALATE, EntityType.TICKET, t.getId());
            log.debug("Ticket {} marked overdue (CRITICAL + past dueDate)", t.getId());
        }
        // else CRITICAL + already overdue → idempotent no-op
    }
}
