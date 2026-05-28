package com.att.tdp.issueflow.comment;

import com.att.tdp.issueflow.comment.dto.MentionsPageDto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users/{userId}/mentions")
@RequiredArgsConstructor
public class MentionController {

    private final CommentService commentService;

    @GetMapping
    public MentionsPageDto list(@PathVariable Long userId,
                                @RequestParam(defaultValue = "1") int page,
                                @RequestParam(defaultValue = "20") int pageSize) {
        if (page < 1) page = 1;
        if (pageSize < 1) pageSize = 20;
        if (pageSize > 100) pageSize = 100;
        return commentService.listMentionsForUser(userId, page, pageSize);
    }
}
