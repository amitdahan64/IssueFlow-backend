package com.att.tdp.issueflow.user.dto;

import com.att.tdp.issueflow.common.domain.Role;
import com.att.tdp.issueflow.user.User;

public record UserResponseDto(
        Long id,
        String username,
        String email,
        String fullName,
        Role role
) {
    public static UserResponseDto from(User user) {
        return new UserResponseDto(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getFullName(),
                user.getRole()
        );
    }
}
