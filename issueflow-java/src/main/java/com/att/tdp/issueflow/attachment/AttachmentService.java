package com.att.tdp.issueflow.attachment;

import com.att.tdp.issueflow.auth.AuthenticatedUser;
import com.att.tdp.issueflow.common.audit.AuditService;
import com.att.tdp.issueflow.common.domain.AuditAction;
import com.att.tdp.issueflow.common.domain.EntityType;
import com.att.tdp.issueflow.common.error.BusinessRuleException;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.common.error.PayloadTooLargeException;
import com.att.tdp.issueflow.ticket.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AttachmentService {

    private final AttachmentRepository repository;
    private final TicketService ticketService;
    private final AttachmentProperties properties;
    private final AuditService auditService;

    @Transactional
    public Attachment upload(Long ticketId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("Uploaded file is empty or missing.");
        }
        ticketService.findById(ticketId); // 404 if missing or soft-deleted

        Set<String> allowed = properties.allowedContentTypeSet();
        String contentType = file.getContentType();
        if (contentType == null || !allowed.contains(contentType)) {
            throw new BusinessRuleException(
                    "Content-type '" + contentType + "' is not allowed. Allowed: " + allowed);
        }

        // Spring's multipart config rejects oversize uploads with 413 in a
        // real servlet container; MockMvc doesn't enforce the cap, so this
        // service-level check is the safety net that also gives tests a
        // consistent 413 response.
        if (file.getSize() > properties.getMaxBytes()) {
            throw new PayloadTooLargeException(
                    "File exceeds maximum size of " + properties.getMaxBytes() + " bytes.");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new BusinessRuleException("Failed to read uploaded file: " + ex.getMessage());
        }

        Attachment a = new Attachment();
        a.setTicketId(ticketId);
        a.setFilename(safeFilename(file.getOriginalFilename()));
        a.setContentType(contentType);
        a.setSizeBytes(file.getSize());
        a.setBytes(bytes);
        a.setUploadedBy(currentUserId());

        Attachment saved = repository.save(a);
        auditService.log(AuditAction.CREATE, EntityType.ATTACHMENT, saved.getId());
        return saved;
    }

    @Transactional
    public void delete(Long ticketId, Long attachmentId) {
        ticketService.findById(ticketId);
        Attachment a = repository.findById(attachmentId)
                .orElseThrow(() -> NotFoundException.of("Attachment", attachmentId));
        if (!a.getTicketId().equals(ticketId)) {
            throw NotFoundException.of("Attachment", attachmentId);
        }
        repository.delete(a);
        auditService.log(AuditAction.DELETE, EntityType.ATTACHMENT, attachmentId);
    }

    private String safeFilename(String original) {
        if (original == null || original.isBlank()) return "attachment";
        // Strip any directory components a client might smuggle in.
        String basename = original.replace('\\', '/');
        int slash = basename.lastIndexOf('/');
        if (slash >= 0) basename = basename.substring(slash + 1);
        return basename.length() > 255 ? basename.substring(0, 255) : basename;
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser p) {
            return p.userId();
        }
        return null;
    }
}
