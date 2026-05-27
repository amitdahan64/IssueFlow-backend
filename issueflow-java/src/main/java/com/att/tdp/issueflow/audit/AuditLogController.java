package com.att.tdp.issueflow.audit;

import com.att.tdp.issueflow.audit.dto.AuditLogResponseDto;
import com.att.tdp.issueflow.common.domain.AuditAction;
import com.att.tdp.issueflow.common.domain.AuditActor;
import com.att.tdp.issueflow.common.domain.EntityType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogRepository repository;

    @GetMapping
    public List<AuditLogResponseDto> list(
            @RequestParam(required = false) EntityType entityType,
            @RequestParam(required = false) Long entityId,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) AuditActor actor) {
        var spec = AuditLogSpecifications.withFilters(entityType, entityId, action, actor);
        var sort = Sort.by(Sort.Direction.DESC, "timestamp");
        return repository.findAll(spec, sort).stream()
                .map(AuditLogResponseDto::from)
                .toList();
    }
}
