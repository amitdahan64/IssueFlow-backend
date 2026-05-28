package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.common.domain.TicketStatus;
import com.att.tdp.issueflow.common.error.BusinessRuleException;
import com.att.tdp.issueflow.ticket.dto.TicketUpdateDto;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Spec 2.4:
 *   - "A ticket can't be updated once it's DONE"
 *   - "A ticket's status may only move forward ... Backward transitions are not allowed"
 */
@Order(0)
@Component
public class LifecycleGuard implements TicketTransitionGuard {

    @Override
    public void verify(Ticket existing, TicketUpdateDto patch) {
        if (existing.getStatus() == TicketStatus.DONE) {
            throw new BusinessRuleException(
                    "Ticket " + existing.getId() + " is DONE; no further updates allowed.");
        }
        if (patch.status() != null && !existing.getStatus().isForwardOrEqual(patch.status())) {
            throw new BusinessRuleException(
                    "Status cannot move from " + existing.getStatus() + " to " + patch.status()
                            + "; lifecycle is forward-only.");
        }
    }
}
