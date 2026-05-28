package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.common.audit.AuditService;
import com.att.tdp.issueflow.common.domain.AuditAction;
import com.att.tdp.issueflow.common.domain.EntityType;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectService;
import com.att.tdp.issueflow.ticket.dto.TicketCreateDto;
import com.att.tdp.issueflow.ticket.dto.TicketUpdateDto;
import com.att.tdp.issueflow.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TicketService {

    private final TicketRepository ticketRepository;
    private final ProjectService projectService;
    private final UserRepository userRepository;
    private final TicketAssignmentResolver assignmentResolver;
    private final List<TicketTransitionGuard> guards;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<Ticket> findAllByProject(Long projectId) {
        // Ensures project exists (and is not soft-deleted) before listing
        projectService.findById(projectId);
        return ticketRepository.findAllByProjectId(projectId);
    }

    @Transactional(readOnly = true)
    public Ticket findById(Long id) {
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Ticket", id));
        if (ticket.isDeleted()) {
            // Defensive: @SQLRestriction filters SQL but the L1 cache can hand
            // back a soft-deleted entity loaded earlier in the same transaction.
            throw NotFoundException.of("Ticket", id);
        }
        return ticket;
    }

    @Transactional(readOnly = true)
    public List<Ticket> findDeletedByProject(Long projectId) {
        return ticketRepository.findAllSoftDeletedByProject(projectId);
    }

    @Transactional
    public Ticket create(TicketCreateDto dto) {
        Project project = projectService.findById(dto.projectId());

        Long resolvedAssignee = assignmentResolver.resolve(dto, project);
        if (resolvedAssignee != null && !userRepository.existsById(resolvedAssignee)) {
            throw NotFoundException.of("User", resolvedAssignee);
        }

        Ticket ticket = new Ticket();
        ticket.setTitle(dto.title());
        ticket.setDescription(dto.description());
        ticket.setStatus(dto.status());
        ticket.setPriority(dto.priority());
        ticket.setType(dto.type());
        ticket.setProjectId(project.getId());
        ticket.setAssigneeId(resolvedAssignee);
        ticket.setDueDate(dto.dueDate());

        Ticket saved = ticketRepository.save(ticket);
        auditService.log(AuditAction.CREATE, EntityType.TICKET, saved.getId());
        return saved;
    }

    @Transactional
    public void update(Long id, TicketUpdateDto dto) {
        Ticket existing = findById(id);

        if (!dto.version().equals(existing.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(
                    "Ticket " + id + " was modified concurrently (expected version "
                            + dto.version() + ", actual " + existing.getVersion() + ").",
                    Ticket.class);
        }

        for (TicketTransitionGuard guard : guards) {
            guard.verify(existing, dto);
        }

        if (dto.title() != null) existing.setTitle(dto.title());
        if (dto.description() != null) existing.setDescription(dto.description());
        if (dto.status() != null) existing.setStatus(dto.status());
        if (dto.assigneeId() != null) {
            if (!userRepository.existsById(dto.assigneeId())) {
                throw NotFoundException.of("User", dto.assigneeId());
            }
            existing.setAssigneeId(dto.assigneeId());
        }
        if (dto.dueDate() != null) existing.setDueDate(dto.dueDate());

        // Spec 3.7: a manual priority change resets the auto-escalation state.
        if (dto.priority() != null && dto.priority() != existing.getPriority()) {
            existing.setPriority(dto.priority());
            existing.setOverdue(false);
        }

        // saveAndFlush forces the @Version bump to materialize immediately so
        // a follow-up request in the same transaction (e.g. in integration
        // tests, or any controller method that updates twice) sees the new
        // version. In production each request is its own tx, so the explicit
        // flush is a no-op cost above the implicit commit-time flush.
        ticketRepository.saveAndFlush(existing);
        auditService.log(AuditAction.UPDATE, EntityType.TICKET, existing.getId());
    }

    @Transactional
    public void softDelete(Long id) {
        Ticket ticket = findById(id);
        ticket.setDeletedAt(Instant.now());
        ticketRepository.save(ticket);
        auditService.log(AuditAction.DELETE, EntityType.TICKET, ticket.getId());
    }

    @Transactional
    public void restore(Long id) {
        Ticket ticket = ticketRepository.findByIdIncludingDeleted(id)
                .orElseThrow(() -> NotFoundException.of("Ticket", id));
        if (!ticket.isDeleted()) {
            throw new com.att.tdp.issueflow.common.error.BusinessRuleException(
                    "Ticket " + id + " is not soft-deleted.");
        }
        ticket.setDeletedAt(null);
        ticketRepository.save(ticket);
        auditService.log(AuditAction.RESTORE, EntityType.TICKET, ticket.getId());
    }
}
