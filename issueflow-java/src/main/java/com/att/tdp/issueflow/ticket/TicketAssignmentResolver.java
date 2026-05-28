package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.ticket.dto.TicketCreateDto;

/**
 * Seam for choosing the assignee at ticket creation time. The Phase-5 default
 * returns {@code dto.assigneeId()} unchanged (no auto-assign). Phase 8 will
 * register a {@code @Primary} bean ({@code AutoAssignService}) that picks the
 * least-loaded DEVELOPER when {@code assigneeId} is null.
 */
public interface TicketAssignmentResolver {
    Long resolve(TicketCreateDto dto, Project project);
}
