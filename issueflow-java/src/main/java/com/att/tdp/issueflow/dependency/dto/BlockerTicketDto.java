package com.att.tdp.issueflow.dependency.dto;

import com.att.tdp.issueflow.common.domain.TicketStatus;
import com.att.tdp.issueflow.ticket.Ticket;

public record BlockerTicketDto(
        Long id,
        String title,
        TicketStatus status
) {
    public static BlockerTicketDto from(Ticket t) {
        return new BlockerTicketDto(t.getId(), t.getTitle(), t.getStatus());
    }
}
