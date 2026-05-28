package com.att.tdp.issueflow.ticket.dto;

import com.att.tdp.issueflow.common.domain.Priority;
import com.att.tdp.issueflow.common.domain.TicketStatus;
import com.att.tdp.issueflow.common.domain.TicketType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record TicketCreateDto(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 2000) String description,
        @NotNull TicketStatus status,
        @NotNull Priority priority,
        @NotNull TicketType type,
        @NotNull Long projectId,
        Long assigneeId,
        Instant dueDate
) {}
