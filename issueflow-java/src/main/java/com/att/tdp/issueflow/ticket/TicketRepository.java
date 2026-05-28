package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.common.domain.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, Long> {

    /** Live tickets only ({@code @SQLRestriction} filters out soft-deleted). */
    List<Ticket> findAllByProjectId(Long projectId);

    /** Bypasses {@code @SQLRestriction} — returns soft-deleted tickets for an ADMIN listing. */
    @Query(value = "SELECT * FROM tickets WHERE project_id = ?1 AND deleted_at IS NOT NULL", nativeQuery = true)
    List<Ticket> findAllSoftDeletedByProject(Long projectId);

    /** Bypasses {@code @SQLRestriction} — used by the restore endpoint. */
    @Query(value = "SELECT * FROM tickets WHERE id = ?1", nativeQuery = true)
    Optional<Ticket> findByIdIncludingDeleted(Long id);

    /**
     * Workload counts: open (non-DONE) tickets per assignee within a project,
     * soft-deleted excluded by {@code @SQLRestriction}. Used by both the
     * workload endpoint and the auto-assignment resolver.
     *
     * Returns rows of {@code [assigneeId, count]} for assignees with at least
     * one open ticket — users with zero open tickets are filled in by the
     * service against the candidate list.
     */
    @Query("""
            SELECT t.assigneeId, COUNT(t)
            FROM Ticket t
            WHERE t.projectId = :projectId
              AND t.status <> :done
              AND t.assigneeId IS NOT NULL
            GROUP BY t.assigneeId
            """)
    List<Object[]> countOpenByAssigneeForProject(Long projectId, TicketStatus done);

    /**
     * Ids of tickets eligible for auto-escalation (spec 3.7). Returns ids only
     * so the scheduler can process each ticket in its own short transaction,
     * giving optimistic-lock retry semantics meaningful boundaries.
     * Soft-deleted tickets are filtered out by {@code @SQLRestriction}.
     */
    @Query("""
            SELECT t.id FROM Ticket t
            WHERE t.dueDate IS NOT NULL
              AND t.dueDate < :now
              AND t.status <> :done
            """)
    List<Long> findOverdueTicketIds(java.time.Instant now, TicketStatus done);
}
