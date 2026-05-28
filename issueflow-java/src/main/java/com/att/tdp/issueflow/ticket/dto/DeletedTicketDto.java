package com.att.tdp.issueflow.ticket.dto;

import com.att.tdp.issueflow.common.domain.Priority;
import com.att.tdp.issueflow.common.domain.TicketStatus;
import com.att.tdp.issueflow.common.domain.TicketType;
import com.att.tdp.issueflow.ticket.Ticket;

/**
 * Trimmed response for the ADMIN-only {@code GET /tickets/deleted} endpoint
 * — matches the README contract exactly.
 */
public record DeletedTicketDto(
        Long id,
        String title,
        TicketStatus status,
        Priority priority,
        TicketType type,
        Long projectId
) {
    public static DeletedTicketDto from(Ticket t) {
        return new DeletedTicketDto(
                t.getId(),
                t.getTitle(),
                t.getStatus(),
                t.getPriority(),
                t.getType(),
                t.getProjectId()
        );
    }
}
