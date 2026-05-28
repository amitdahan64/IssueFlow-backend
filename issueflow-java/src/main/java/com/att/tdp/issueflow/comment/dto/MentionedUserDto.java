package com.att.tdp.issueflow.comment.dto;

import com.att.tdp.issueflow.user.User;

public record MentionedUserDto(
        Long id,
        String username,
        String fullName
) {
    public static MentionedUserDto from(User user) {
        return new MentionedUserDto(user.getId(), user.getUsername(), user.getFullName());
    }
}
