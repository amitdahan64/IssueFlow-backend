package com.att.tdp.issueflow.dependency;

import com.att.tdp.issueflow.common.audit.AuditService;
import com.att.tdp.issueflow.common.domain.AuditAction;
import com.att.tdp.issueflow.common.domain.EntityType;
import com.att.tdp.issueflow.common.error.BusinessRuleException;
import com.att.tdp.issueflow.common.error.ConflictException;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TicketDependencyService {

    private final TicketDependencyRepository repository;
    private final TicketService ticketService;
    private final AuditService auditService;

    @Transactional
    public TicketDependency add(Long ticketId, Long blockerId) {
        if (ticketId.equals(blockerId)) {
            throw new BusinessRuleException("A ticket cannot block itself.");
        }
        Ticket target  = ticketService.findById(ticketId);
        Ticket blocker = ticketService.findById(blockerId);

        if (!target.getProjectId().equals(blocker.getProjectId())) {
            throw new BusinessRuleException(
                    "Both tickets must belong to the same project (ticket "
                            + ticketId + " in project " + target.getProjectId()
                            + ", blocker " + blockerId + " in project " + blocker.getProjectId() + ").");
        }
        if (repository.existsByTicketIdAndBlockerTicketId(ticketId, blockerId)) {
            throw new ConflictException(
                    "Ticket " + ticketId + " is already blocked by " + blockerId + ".");
        }

        TicketDependency dep = new TicketDependency();
        dep.setTicketId(ticketId);
        dep.setBlockerTicketId(blockerId);
        TicketDependency saved = repository.save(dep);
        auditService.log(AuditAction.CREATE, EntityType.DEPENDENCY, saved.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Ticket> listBlockers(Long ticketId) {
        // Ensure the ticket exists (and is not soft-deleted).
        ticketService.findById(ticketId);

        return repository.findAllByTicketId(ticketId).stream()
                .map(dep -> ticketService.findById(dep.getBlockerTicketId()))
                .toList();
    }

    @Transactional
    public void remove(Long ticketId, Long blockerId) {
        // Verify ticket exists for a sensible 404 response on a bogus path.
        ticketService.findById(ticketId);

        TicketDependency dep = repository.findByTicketIdAndBlockerTicketId(ticketId, blockerId)
                .orElseThrow(() -> new NotFoundException(
                        "Dependency (ticket " + ticketId + ", blocker " + blockerId + ") not found."));

        Long depId = dep.getId();
        repository.delete(dep);
        auditService.log(AuditAction.DELETE, EntityType.DEPENDENCY, depId);
    }
}
