package com.att.tdp.issueflow.comment.dto;

import com.att.tdp.issueflow.comment.Comment;

import java.util.List;

public record CommentResponseDto(
        Long id,
        Long ticketId,
        Long authorId,
        String content,
        List<MentionedUserDto> mentionedUsers,
        Long version
) {
    public static CommentResponseDto from(Comment c, List<MentionedUserDto> mentions) {
        return new CommentResponseDto(
                c.getId(),
                c.getTicketId(),
                c.getAuthorId(),
                c.getContent(),
                mentions,
                c.getVersion()
        );
    }
}
