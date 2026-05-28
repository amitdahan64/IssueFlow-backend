package com.att.tdp.issueflow.dependency;

import com.att.tdp.issueflow.common.domain.TicketStatus;
import com.att.tdp.issueflow.common.error.BusinessRuleException;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketRepository;
import com.att.tdp.issueflow.ticket.TicketTransitionGuard;
import com.att.tdp.issueflow.ticket.dto.TicketUpdateDto;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Spec 3.2: "A ticket cannot transition to DONE if it has unresolved blockers."
 *
 * Plugged in via the {@link TicketTransitionGuard} seam introduced in Phase 5 —
 * {@code TicketService} injects {@code List<TicketTransitionGuard>} and runs them
 * in {@code @Order}. We use a direct {@code TicketRepository} dep (not
 * {@code TicketService}) to avoid the bean-cycle that would form otherwise.
 *
 * Blockers that have been soft-deleted are treated as resolved.
 */
@Order(10)
@Component
@RequiredArgsConstructor
public class DependencyBlockerGuard implements TicketTransitionGuard {

    private final TicketDependencyRepository dependencyRepository;
    private final TicketRepository ticketRepository;

    @Override
    public void verify(Ticket existing, TicketUpdateDto patch) {
        if (patch.status() != TicketStatus.DONE) {
            return;
        }
        for (TicketDependency dep : dependencyRepository.findAllByTicketId(existing.getId())) {
            Optional<Ticket> blocker = ticketRepository.findById(dep.getBlockerTicketId());
            if (blocker.isEmpty()) {
                continue; // soft-deleted blocker no longer blocks
            }
            Ticket b = blocker.get();
            if (b.getStatus() != TicketStatus.DONE) {
                throw new BusinessRuleException(
                        "Cannot move ticket " + existing.getId() + " to DONE — blocker "
                                + b.getId() + " is not yet DONE (status: " + b.getStatus() + ").");
            }
        }
    }
}
