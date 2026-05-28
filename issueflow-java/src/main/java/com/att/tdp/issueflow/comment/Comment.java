package com.att.tdp.issueflow.comment;

import com.att.tdp.issueflow.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "comments", indexes = {
        @Index(name = "ix_comments_ticket", columnList = "ticket_id"),
        @Index(name = "ix_comments_author", columnList = "author_id")
})
public class Comment extends BaseEntity {

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Column(nullable = false, length = 2000)
    private String content;

    @Version
    private Long version;
}
