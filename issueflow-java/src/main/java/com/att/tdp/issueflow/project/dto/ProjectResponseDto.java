package com.att.tdp.issueflow.project.dto;

import com.att.tdp.issueflow.project.Project;

public record ProjectResponseDto(
        Long id,
        String name,
        String description,
        Long ownerId
) {
    public static ProjectResponseDto from(Project project) {
        return new ProjectResponseDto(
                project.getId(),
                project.getName(),
                project.getDescription(),
                project.getOwnerId()
        );
    }
}
