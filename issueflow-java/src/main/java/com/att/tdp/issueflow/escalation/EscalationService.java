package com.att.tdp.issueflow.escalation;

import com.att.tdp.issueflow.common.domain.TicketStatus;
import com.att.tdp.issueflow.ticket.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Spec 3.7 — orchestrates one escalation sweep. The actual per-ticket
 * mutation happens in {@link EscalationStep} so {@code @Retryable} is
 * effective and so each ticket runs in its own transaction.
 */
@Service
@RequiredArgsConstructor
public class EscalationService {

    private static final Logger log = LoggerFactory.getLogger(EscalationService.class);

    private final TicketRepository ticketRepository;
    private final EscalationStep step;

    /**
     * Reads the overdue-ticket ids in a short read-only transaction so we
     * don't hold a long-lived snapshot while the per-ticket steps run.
     */
    @Transactional(readOnly = true)
    public List<Long> findOverdueIds(Instant now) {
        return ticketRepository.findOverdueTicketIds(now, TicketStatus.DONE);
    }

    /** Returns the number of tickets processed (whether changed or not). */
    public int runOnce() {
        Instant now = Instant.now();
        List<Long> ids = findOverdueIds(now);
        for (Long id : ids) {
            try {
                step.escalateOne(id, now);
            } catch (Exception ex) {
                log.warn("Auto-escalation failed for ticket {}: {}", id, ex.getMessage());
            }
        }
        return ids.size();
    }
}
