package com.att.tdp.issueflow.workload;

import com.att.tdp.issueflow.common.domain.Role;
import com.att.tdp.issueflow.common.domain.TicketStatus;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectService;
import com.att.tdp.issueflow.project.dto.WorkloadEntryDto;
import com.att.tdp.issueflow.ticket.TicketRepository;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WorkloadService {

    private final ProjectService projectService;
    private final UserRepository userRepository;
    private final TicketRepository ticketRepository;

    /**
     * Counts of open (non-DONE, non-soft-deleted) tickets per assignee within
     * the project, keyed by user id. Used by both the workload endpoint and
     * the auto-assignment resolver.
     */
    @Transactional(readOnly = true)
    public Map<Long, Long> openTicketCounts(Long projectId) {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : ticketRepository.countOpenByAssigneeForProject(projectId, TicketStatus.DONE)) {
            counts.put((Long) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    /**
     * Workload report per the spec — every DEVELOPER plus the project owner,
     * sorted ASC by openTicketCount. Users with zero open tickets are
     * included with count 0.
     */
    @Transactional(readOnly = true)
    public List<WorkloadEntryDto> computeForProject(Long projectId) {
        Project project = projectService.findById(projectId);
        Map<Long, Long> counts = openTicketCounts(projectId);

        // Use a LinkedHashMap so we don't double-list the owner if they are
        // already a DEVELOPER.
        Map<Long, User> members = new LinkedHashMap<>();
        for (User dev : userRepository.findAllByRoleOrderByCreatedAtAsc(Role.DEVELOPER)) {
            members.put(dev.getId(), dev);
        }
        userRepository.findById(project.getOwnerId()).ifPresent(o ->
                members.putIfAbsent(o.getId(), o));

        return members.values().stream()
                .map(u -> new WorkloadEntryDto(
                        u.getId(),
                        u.getUsername(),
                        counts.getOrDefault(u.getId(), 0L)))
                .sorted(Comparator.comparingLong(WorkloadEntryDto::openTicketCount))
                .toList();
    }
}
