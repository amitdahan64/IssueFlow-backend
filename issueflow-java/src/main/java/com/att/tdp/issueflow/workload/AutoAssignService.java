package com.att.tdp.issueflow.workload;

import com.att.tdp.issueflow.common.domain.Role;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.ticket.TicketAssignmentResolver;
import com.att.tdp.issueflow.ticket.dto.TicketCreateDto;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Spec 3.8: when {@code POST /tickets} omits {@code assigneeId} the system
 * auto-assigns the least-loaded DEVELOPER. Ties resolve by oldest registration
 * (the user repo returns devs already ordered by {@code createdAt} ASC, so
 * picking the first element with the minimum count is enough).
 *
 * If the client supplied an explicit assignee, this resolver returns it
 * unchanged — no auto-assign side effect.
 * If no DEVELOPER exists, returns {@code null} (no error per spec).
 *
 * Wired in via the {@link TicketAssignmentResolver} seam from Phase 5 — the
 * Phase-5 default resolver has been removed so this is the only impl Spring
 * finds.
 */
@Service
@RequiredArgsConstructor
public class AutoAssignService implements TicketAssignmentResolver {

    private final UserRepository userRepository;
    private final WorkloadService workloadService;

    @Override
    public Long resolve(TicketCreateDto dto, Project project) {
        if (dto.assigneeId() != null) {
            return dto.assigneeId();
        }
        List<User> devs = userRepository.findAllByRoleOrderByCreatedAtAsc(Role.DEVELOPER);
        if (devs.isEmpty()) {
            return null;
        }
        Map<Long, Long> counts = workloadService.openTicketCounts(project.getId());
        return devs.stream()
                .min(Comparator.comparingLong(u -> counts.getOrDefault(u.getId(), 0L)))
                .map(User::getId)
                .orElse(null);
    }
}
