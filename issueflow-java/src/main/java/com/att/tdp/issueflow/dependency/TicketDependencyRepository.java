package com.att.tdp.issueflow.dependency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TicketDependencyRepository extends JpaRepository<TicketDependency, Long> {

    List<TicketDependency> findAllByTicketId(Long ticketId);

    Optional<TicketDependency> findByTicketIdAndBlockerTicketId(Long ticketId, Long blockerTicketId);

    boolean existsByTicketIdAndBlockerTicketId(Long ticketId, Long blockerTicketId);
}
