package com.att.tdp.issueflow.escalation;

import com.att.tdp.issueflow.audit.AuditLog;
import com.att.tdp.issueflow.audit.AuditLogRepository;
import com.att.tdp.issueflow.common.domain.AuditAction;
import com.att.tdp.issueflow.common.domain.AuditActor;
import com.att.tdp.issueflow.common.domain.EntityType;
import com.att.tdp.issueflow.common.domain.Priority;
import com.att.tdp.issueflow.common.domain.Role;
import com.att.tdp.issueflow.common.domain.TicketStatus;
import com.att.tdp.issueflow.common.domain.TicketType;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketRepository;
import com.att.tdp.issueflow.ticket.dto.TicketUpdateDto;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No {@code @Transactional} on the class — {@link EscalationStep} uses
 * {@code REQUIRES_NEW} so its writes commit independently. We clean state
 * manually in {@code @BeforeEach} instead.
 */
@SpringBootTest
@ActiveProfiles("test")
class EscalationServiceTest {

    @Autowired private EscalationService escalationService;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private com.att.tdp.issueflow.ticket.TicketService ticketService;

    private Project project;
    private User owner;

    @BeforeEach
    void clean() {
        auditRepository.deleteAll();
        ticketRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();

        owner = new User();
        owner.setUsername("owner");
        owner.setEmail("owner@example.com");
        owner.setFullName("Owner");
        owner.setRole(Role.DEVELOPER);
        owner.setPasswordHash(passwordEncoder.encode("secret"));
        owner = userRepository.saveAndFlush(owner);

        Project p = new Project();
        p.setName("Alpha");
        p.setOwnerId(owner.getId());
        project = projectRepository.saveAndFlush(p);
    }

    private Ticket seedTicket(Priority priority, TicketStatus status, Instant dueDate) {
        Ticket t = new Ticket();
        t.setTitle("T");
        t.setStatus(status);
        t.setPriority(priority);
        t.setType(TicketType.BUG);
        t.setProjectId(project.getId());
        t.setDueDate(dueDate);
        return ticketRepository.saveAndFlush(t);
    }

    private Ticket reload(Long id) {
        return ticketRepository.findById(id).orElseThrow();
    }

    private List<AuditLog> escalateAudits(Long ticketId) {
        return auditRepository.findAll().stream()
                .filter(r -> r.getAction() == AuditAction.AUTO_ESCALATE
                        && r.getEntityType() == EntityType.TICKET
                        && r.getEntityId() == ticketId)
                .toList();
    }

    @Test
    void overdue_LOW_walks_to_CRITICAL_then_overdue_then_idempotent() {
        Ticket t = seedTicket(Priority.LOW, TicketStatus.TODO,
                Instant.now().minus(1, ChronoUnit.HOURS));

        escalationService.runOnce();
        Ticket s1 = reload(t.getId());
        assertThat(s1.getPriority()).isEqualTo(Priority.MEDIUM);
        assertThat(s1.isOverdue()).isFalse();

        escalationService.runOnce();
        Ticket s2 = reload(t.getId());
        assertThat(s2.getPriority()).isEqualTo(Priority.HIGH);
        assertThat(s2.isOverdue()).isFalse();

        escalationService.runOnce();
        Ticket s3 = reload(t.getId());
        assertThat(s3.getPriority()).isEqualTo(Priority.CRITICAL);
        assertThat(s3.isOverdue()).isFalse();

        // Now CRITICAL + still overdue → next tick flips isOverdue
        escalationService.runOnce();
        Ticket s4 = reload(t.getId());
        assertThat(s4.getPriority()).isEqualTo(Priority.CRITICAL);
        assertThat(s4.isOverdue()).isTrue();

        long versionBefore = s4.getVersion();

        // Idempotent — another tick changes nothing
        escalationService.runOnce();
        Ticket s5 = reload(t.getId());
        assertThat(s5.getPriority()).isEqualTo(Priority.CRITICAL);
        assertThat(s5.isOverdue()).isTrue();
        assertThat(s5.getVersion()).isEqualTo(versionBefore);

        // Exactly 4 AUTO_ESCALATE audit rows (LOW→MEDIUM, MEDIUM→HIGH, HIGH→CRITICAL, set isOverdue)
        assertThat(escalateAudits(t.getId())).hasSize(4)
                .allSatisfy(r -> {
                    assertThat(r.getActor()).isEqualTo(AuditActor.SYSTEM);
                    assertThat(r.getPerformedBy()).isNull();
                });
    }

    @Test
    void status_field_is_never_modified_by_escalation() {
        Ticket t = seedTicket(Priority.LOW, TicketStatus.IN_PROGRESS,
                Instant.now().minus(1, ChronoUnit.HOURS));

        escalationService.runOnce();
        assertThat(reload(t.getId()).getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
    }

    @Test
    void DONE_tickets_are_skipped() {
        Ticket t = seedTicket(Priority.LOW, TicketStatus.DONE,
                Instant.now().minus(1, ChronoUnit.HOURS));

        escalationService.runOnce();

        Ticket after = reload(t.getId());
        assertThat(after.getPriority()).isEqualTo(Priority.LOW);
        assertThat(after.isOverdue()).isFalse();
        assertThat(escalateAudits(t.getId())).isEmpty();
    }

    @Test
    void tickets_without_a_dueDate_are_skipped() {
        Ticket t = seedTicket(Priority.LOW, TicketStatus.TODO, /*dueDate=*/ null);

        escalationService.runOnce();

        assertThat(reload(t.getId()).getPriority()).isEqualTo(Priority.LOW);
        assertThat(escalateAudits(t.getId())).isEmpty();
    }

    @Test
    void tickets_with_future_dueDate_are_skipped() {
        Ticket t = seedTicket(Priority.MEDIUM, TicketStatus.TODO,
                Instant.now().plus(1, ChronoUnit.HOURS));

        escalationService.runOnce();

        assertThat(reload(t.getId()).getPriority()).isEqualTo(Priority.MEDIUM);
        assertThat(escalateAudits(t.getId())).isEmpty();
    }

    @Test
    void soft_deleted_tickets_are_skipped() {
        Ticket t = seedTicket(Priority.LOW, TicketStatus.TODO,
                Instant.now().minus(1, ChronoUnit.HOURS));
        t.setDeletedAt(Instant.now());
        ticketRepository.saveAndFlush(t);

        escalationService.runOnce();

        // Re-fetch via includingDeleted so @SQLRestriction doesn't hide it from us.
        Ticket after = ticketRepository.findByIdIncludingDeleted(t.getId()).orElseThrow();
        assertThat(after.getPriority()).isEqualTo(Priority.LOW);
    }

    @Test
    void manual_priority_change_resets_isOverdue_then_next_tick_works_from_new_priority() {
        // Seed a CRITICAL+overdue ticket (4 ticks worth of state)
        Ticket t = seedTicket(Priority.CRITICAL, TicketStatus.IN_PROGRESS,
                Instant.now().minus(1, ChronoUnit.HOURS));
        t.setOverdue(true);
        ticketRepository.saveAndFlush(t);
        // Reload to read the post-flush @Version reliably outside any test-owned tx.
        long version = reload(t.getId()).getVersion();

        // User manually demotes priority — should clear isOverdue (Phase 5 logic).
        ticketService.update(t.getId(),
                new TicketUpdateDto(null, null, null, Priority.LOW, null, null, version));

        Ticket after = reload(t.getId());
        assertThat(after.getPriority()).isEqualTo(Priority.LOW);
        assertThat(after.isOverdue()).isFalse();

        // Next escalation tick walks priority up from the new baseline.
        escalationService.runOnce();
        assertThat(reload(t.getId()).getPriority()).isEqualTo(Priority.MEDIUM);
    }

    @Test
    void runOnce_returns_the_number_of_overdue_tickets_processed() {
        seedTicket(Priority.LOW, TicketStatus.TODO,
                Instant.now().minus(1, ChronoUnit.HOURS));
        seedTicket(Priority.MEDIUM, TicketStatus.IN_REVIEW,
                Instant.now().minus(2, ChronoUnit.HOURS));
        // Not overdue:
        seedTicket(Priority.LOW, TicketStatus.TODO, /*dueDate=*/ null);
        seedTicket(Priority.LOW, TicketStatus.DONE,
                Instant.now().minus(1, ChronoUnit.HOURS));

        int processed = escalationService.runOnce();
        // Found ids list contains only the 2 eligible overdue tickets
        assertThat(processed).isEqualTo(2);
    }
}
