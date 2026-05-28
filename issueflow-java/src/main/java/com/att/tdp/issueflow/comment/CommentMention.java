package com.att.tdp.issueflow.comment;

import com.att.tdp.issueflow.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "comment_mentions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_comment_mention",
                columnNames = {"comment_id", "mentioned_user_id"}
        ),
        indexes = {
                @Index(name = "ix_mention_comment", columnList = "comment_id"),
                @Index(name = "ix_mention_user", columnList = "mentioned_user_id")
        })
public class CommentMention extends BaseEntity {

    @Column(name = "comment_id", nullable = false)
    private Long commentId;

    @Column(name = "mentioned_user_id", nullable = false)
    private Long mentionedUserId;
}
