package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.common.error.BusinessRuleException;
import com.att.tdp.issueflow.ticket.dto.TicketUpdateDto;

/**
 * Composable guard run by TicketService before applying a PATCH. Multiple
 * implementations form a chain — Phase 5 ships {@link LifecycleGuard}; Phase 6
 * adds a dependency-blocker guard. Implementations throw
 * {@link BusinessRuleException} to reject the transition.
 */
public interface TicketTransitionGuard {
    void verify(Ticket existing, TicketUpdateDto patch);
}
