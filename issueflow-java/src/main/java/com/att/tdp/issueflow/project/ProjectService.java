package com.att.tdp.issueflow.project;

import com.att.tdp.issueflow.common.audit.AuditService;
import com.att.tdp.issueflow.common.domain.AuditAction;
import com.att.tdp.issueflow.common.domain.EntityType;
import com.att.tdp.issueflow.common.error.BusinessRuleException;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.project.dto.ProjectCreateDto;
import com.att.tdp.issueflow.project.dto.ProjectUpdateDto;
import com.att.tdp.issueflow.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<Project> findAll() {
        return projectRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Project findById(Long id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Project", id));
        // Defensive: @SQLRestriction filters SQL, but Hibernate's L1 cache
        // can return a soft-deleted entity loaded earlier in the same tx.
        if (project.isDeleted()) {
            throw NotFoundException.of("Project", id);
        }
        return project;
    }

    @Transactional(readOnly = true)
    public List<Project> findAllDeleted() {
        return projectRepository.findAllSoftDeleted();
    }

    @Transactional
    public Project create(ProjectCreateDto dto) {
        if (!userRepository.existsById(dto.ownerId())) {
            throw NotFoundException.of("User", dto.ownerId());
        }
        Project project = new Project();
        project.setName(dto.name());
        project.setDescription(dto.description());
        project.setOwnerId(dto.ownerId());

        Project saved = projectRepository.save(project);
        auditService.log(AuditAction.CREATE, EntityType.PROJECT, saved.getId());
        return saved;
    }

    @Transactional
    public void update(Long id, ProjectUpdateDto dto) {
        Project project = findById(id);
        if (dto.name() != null) {
            if (dto.name().isBlank()) {
                throw new BusinessRuleException("Project name must not be blank.");
            }
            project.setName(dto.name());
        }
        if (dto.description() != null) {
            project.setDescription(dto.description());
        }
        projectRepository.save(project);
        auditService.log(AuditAction.UPDATE, EntityType.PROJECT, project.getId());
    }

    @Transactional
    public void softDelete(Long id) {
        Project project = findById(id);
        project.setDeletedAt(Instant.now());
        projectRepository.save(project);
        auditService.log(AuditAction.DELETE, EntityType.PROJECT, project.getId());
    }

    @Transactional
    public void restore(Long id) {
        Project project = projectRepository.findByIdIncludingDeleted(id)
                .orElseThrow(() -> NotFoundException.of("Project", id));
        if (!project.isDeleted()) {
            throw new BusinessRuleException("Project " + id + " is not soft-deleted.");
        }
        project.setDeletedAt(null);
        projectRepository.save(project);
        auditService.log(AuditAction.RESTORE, EntityType.PROJECT, project.getId());
    }
}
