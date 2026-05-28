package com.att.tdp.issueflow.dependency.dto;

import jakarta.validation.constraints.NotNull;

public record CreateDependencyDto(
        @NotNull Long blockedBy
) {}
