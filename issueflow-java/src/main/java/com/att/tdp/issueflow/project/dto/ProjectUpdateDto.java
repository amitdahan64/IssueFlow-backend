package com.att.tdp.issueflow.project.dto;

import jakarta.validation.constraints.Size;

/**
 * Partial update payload — null fields are ignored. A field that is
 * present but blank (e.g. "name": "") is rejected by ProjectService.
 */
public record ProjectUpdateDto(
        @Size(max = 255) String name,
        @Size(max = 2000) String description
) {}
