package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.common.config.JpaAuditingConfig;
import com.att.tdp.issueflow.common.domain.Priority;
import com.att.tdp.issueflow.common.domain.TicketStatus;
import com.att.tdp.issueflow.common.domain.TicketType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase
@Import(JpaAuditingConfig.class)
class TicketRepositoryTest {

    @Autowired private TicketRepository repository;
    @Autowired private TestEntityManager em;

    private Ticket newTicket(String title, Long projectId, TicketStatus status) {
        Ticket t = new Ticket();
        t.setTitle(title);
        t.setDescription("desc");
        t.setStatus(status);
        t.setPriority(Priority.MEDIUM);
        t.setType(TicketType.BUG);
        t.setProjectId(projectId);
        return t;
    }

    @BeforeEach
    void clean() {
        em.getEntityManager().createNativeQuery("DELETE FROM tickets").executeUpdate();
    }

    @Test
    void findAllByProjectId_excludes_other_projects_and_soft_deleted() {
        Ticket a = repository.saveAndFlush(newTicket("A", 1L, TicketStatus.TODO));
        repository.saveAndFlush(newTicket("B", 2L, TicketStatus.TODO));
        Ticket dead = repository.saveAndFlush(newTicket("Dead", 1L, TicketStatus.TODO));
        dead.setDeletedAt(Instant.now());
        repository.saveAndFlush(dead);
        em.clear();

        List<Ticket> result = repository.findAllByProjectId(1L);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(a.getId());
    }

    @Test
    void findAllSoftDeletedByProject_returns_only_deleted_in_project() {
        Ticket a = repository.saveAndFlush(newTicket("A", 1L, TicketStatus.TODO));
        Ticket dead = repository.saveAndFlush(newTicket("Dead", 1L, TicketStatus.TODO));
        Ticket otherProjectDead = repository.saveAndFlush(newTicket("Other", 2L, TicketStatus.TODO));
        dead.setDeletedAt(Instant.now());
        otherProjectDead.setDeletedAt(Instant.now());
        repository.saveAndFlush(dead);
        repository.saveAndFlush(otherProjectDead);
        em.clear();

        List<Ticket> deleted = repository.findAllSoftDeletedByProject(1L);
        assertThat(deleted).singleElement().satisfies(t -> {
            assertThat(t.getId()).isEqualTo(dead.getId());
            assertThat(t.getProjectId()).isEqualTo(1L);
        });
        assertThat(deleted).noneMatch(t -> t.getId().equals(a.getId()));
    }

    @Test
    void findByIdIncludingDeleted_returns_soft_deleted_row() {
        Ticket t = repository.saveAndFlush(newTicket("Dead", 1L, TicketStatus.TODO));
        t.setDeletedAt(Instant.now());
        repository.saveAndFlush(t);
        em.clear();

        assertThat(repository.findByIdIncludingDeleted(t.getId()))
                .hasValueSatisfying(found -> assertThat(found.isDeleted()).isTrue());
    }

    @Test
    void version_is_initialized_on_persist() {
        Ticket t = repository.saveAndFlush(newTicket("A", 1L, TicketStatus.TODO));
        assertThat(t.getVersion()).isEqualTo(0L);
    }
}
