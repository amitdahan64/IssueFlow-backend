package com.att.tdp.issueflow.comment;

import com.att.tdp.issueflow.comment.dto.CommentCreateDto;
import com.att.tdp.issueflow.comment.dto.CommentResponseDto;
import com.att.tdp.issueflow.comment.dto.CommentUpdateDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/tickets/{ticketId}/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    @GetMapping
    public List<CommentResponseDto> list(@PathVariable Long ticketId) {
        return commentService.listForTicket(ticketId);
    }

    @PostMapping
    public CommentResponseDto create(@PathVariable Long ticketId,
                                     @Valid @RequestBody CommentCreateDto body) {
        return commentService.create(ticketId, body);
    }

    @PatchMapping("/{commentId}")
    public ResponseEntity<Void> update(@PathVariable Long ticketId,
                                       @PathVariable Long commentId,
                                       @Valid @RequestBody CommentUpdateDto body) {
        commentService.update(ticketId, commentId, body);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> delete(@PathVariable Long ticketId,
                                       @PathVariable Long commentId) {
        commentService.delete(ticketId, commentId);
        return ResponseEntity.ok().build();
    }
}
