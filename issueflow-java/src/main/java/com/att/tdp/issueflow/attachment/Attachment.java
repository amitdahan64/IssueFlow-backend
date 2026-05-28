package com.att.tdp.issueflow.attachment;

import com.att.tdp.issueflow.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "attachments", indexes = {
        @Index(name = "ix_attachments_ticket", columnList = "ticket_id")
})
public class Attachment extends BaseEntity {

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    @Column(nullable = false, length = 255)
    private String filename;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    // No @Lob — Hibernate 6 maps byte[] to VARBINARY (or BYTEA on Postgres)
    // by default. Adding @Lob on H2 in PG-compat mode misroutes it to INTEGER.
    @Column(name = "bytes", nullable = false)
    private byte[] bytes;

    @Column(name = "uploaded_by")
    private Long uploadedBy;
}
