package com.att.tdp.issueflow.attachment;

import com.att.tdp.issueflow.attachment.dto.AttachmentResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/tickets/{ticketId}/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService service;

    @PostMapping
    public AttachmentResponseDto upload(@PathVariable Long ticketId,
                                        @RequestParam("file") MultipartFile file) {
        return AttachmentResponseDto.from(service.upload(ticketId, file));
    }

    @DeleteMapping("/{attachmentId}")
    public ResponseEntity<Void> delete(@PathVariable Long ticketId,
                                       @PathVariable Long attachmentId) {
        service.delete(ticketId, attachmentId);
        return ResponseEntity.ok().build();
    }
}
