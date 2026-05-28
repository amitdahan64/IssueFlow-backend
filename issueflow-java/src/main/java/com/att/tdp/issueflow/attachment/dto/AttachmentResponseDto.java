package com.att.tdp.issueflow.attachment.dto;

import com.att.tdp.issueflow.attachment.Attachment;

public record AttachmentResponseDto(
        Long id,
        Long ticketId,
        String filename,
        String contentType
) {
    public static AttachmentResponseDto from(Attachment a) {
        return new AttachmentResponseDto(
                a.getId(),
                a.getTicketId(),
                a.getFilename(),
                a.getContentType()
        );
    }
}
