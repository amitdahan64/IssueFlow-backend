package com.att.tdp.issueflow.project;

import com.att.tdp.issueflow.project.dto.ProjectCreateDto;
import com.att.tdp.issueflow.project.dto.ProjectResponseDto;
import com.att.tdp.issueflow.project.dto.ProjectUpdateDto;
import com.att.tdp.issueflow.project.dto.WorkloadEntryDto;
import com.att.tdp.issueflow.workload.WorkloadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final WorkloadService workloadService;

    @GetMapping
    public List<ProjectResponseDto> getAll() {
        return projectService.findAll().stream().map(ProjectResponseDto::from).toList();
    }

    @GetMapping("/{projectId}")
    public ProjectResponseDto getById(@PathVariable Long projectId) {
        return ProjectResponseDto.from(projectService.findById(projectId));
    }

    @PostMapping
    public ProjectResponseDto create(@Valid @RequestBody ProjectCreateDto dto) {
        return ProjectResponseDto.from(projectService.create(dto));
    }

    @PatchMapping("/{projectId}")
    public ResponseEntity<Void> update(@PathVariable Long projectId,
                                       @Valid @RequestBody ProjectUpdateDto dto) {
        projectService.update(projectId, dto);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> softDelete(@PathVariable Long projectId) {
        projectService.softDelete(projectId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/deleted")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ProjectResponseDto> listDeleted() {
        return projectService.findAllDeleted().stream().map(ProjectResponseDto::from).toList();
    }

    @PostMapping("/{projectId}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> restore(@PathVariable Long projectId) {
        projectService.restore(projectId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{projectId}/workload")
    public List<WorkloadEntryDto> workload(@PathVariable Long projectId) {
        return workloadService.computeForProject(projectId);
    }
}
