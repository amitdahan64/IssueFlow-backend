package com.att.tdp.issueflow.comment.dto;

import java.util.List;

public record MentionsPageDto(
        List<CommentResponseDto> data,
        long total,
        int page
) {}
