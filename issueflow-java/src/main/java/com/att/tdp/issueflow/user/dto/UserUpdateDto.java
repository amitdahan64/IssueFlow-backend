package com.att.tdp.issueflow.user.dto;

import com.att.tdp.issueflow.common.domain.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UserUpdateDto(
        @NotBlank
        @Size(max = 255)
        String fullName,

        @NotNull
        Role role
) {}
