package com.att.tdp.issueflow.ticket.dto;

import com.att.tdp.issueflow.common.domain.Priority;
import com.att.tdp.issueflow.common.domain.TicketStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Partial update payload. Null fields are skipped.
 * {@code version} is required and is checked against the current row's {@code @Version}
 * to detect concurrent modifications.
 */
public record TicketUpdateDto(
        @Size(max = 255) String title,
        @Size(max = 2000) String description,
        TicketStatus status,
        Priority priority,
        Long assigneeId,
        Instant dueDate,
        @NotNull Long version
) {}
