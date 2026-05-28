package com.att.tdp.issueflow.ticket;

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
}
