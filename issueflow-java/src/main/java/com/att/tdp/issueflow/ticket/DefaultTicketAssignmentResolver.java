package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.ticket.dto.TicketCreateDto;
import org.springframework.stereotype.Service;

@Service
public class DefaultTicketAssignmentResolver implements TicketAssignmentResolver {
    @Override
    public Long resolve(TicketCreateDto dto, Project project) {
        return dto.assigneeId();
    }
}
