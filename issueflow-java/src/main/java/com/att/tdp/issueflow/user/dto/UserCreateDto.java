package com.att.tdp.issueflow.user.dto;

import com.att.tdp.issueflow.common.domain.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UserCreateDto(
        @NotBlank
        @Size(min = 3, max = 64)
        @Pattern(regexp = "^[A-Za-z0-9_.-]+$",
                message = "username must contain only letters, digits, '.', '_' or '-'")
        String username,

        @NotBlank
        @Email
        @Size(max = 255)
        String email,

        @NotBlank
        @Size(max = 255)
        String fullName,

        @NotNull
        Role role,

        @NotBlank
        @Size(min = 6, max = 72)
        String password
) {}
