package com.att.tdp.issueflow.ticket.dto;

import com.att.tdp.issueflow.common.domain.Priority;
import com.att.tdp.issueflow.common.domain.TicketStatus;
import com.att.tdp.issueflow.common.domain.TicketType;
import com.att.tdp.issueflow.ticket.Ticket;

import java.time.Instant;

public record TicketResponseDto(
        Long id,
        String title,
        String description,
        TicketStatus status,
        Priority priority,
        TicketType type,
        Long projectId,
        Long assigneeId,
        Instant dueDate,
        boolean isOverdue,
        Long version
) {
    public static TicketResponseDto from(Ticket t) {
        return new TicketResponseDto(
                t.getId(),
                t.getTitle(),
                t.getDescription(),
                t.getStatus(),
                t.getPriority(),
                t.getType(),
                t.getProjectId(),
                t.getAssigneeId(),
                t.getDueDate(),
                t.isOverdue(),
                t.getVersion()
        );
    }
}
