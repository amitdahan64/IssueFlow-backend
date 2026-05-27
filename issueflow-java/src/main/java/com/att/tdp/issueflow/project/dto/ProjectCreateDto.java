package com.att.tdp.issueflow.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ProjectCreateDto(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 2000) String description,
        @NotNull Long ownerId
) {}
