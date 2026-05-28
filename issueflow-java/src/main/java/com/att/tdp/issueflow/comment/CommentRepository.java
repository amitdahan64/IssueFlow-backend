package com.att.tdp.issueflow.comment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommentRepository extends JpaRepository<Comment, Long> {

    List<Comment> findAllByTicketIdOrderByCreatedAtAsc(Long ticketId);

    /**
     * Comments mentioning a given user, ordered newest-first.
     * Joins via the {@code comment_mentions} table.
     */
    @Query("""
            SELECT c FROM Comment c
            WHERE c.id IN (
              SELECT m.commentId FROM CommentMention m WHERE m.mentionedUserId = :userId
            )
            ORDER BY c.createdAt DESC
            """)
    List<Comment> findMentionsForUser(@Param("userId") Long userId,
                                      org.springframework.data.domain.Pageable pageable);

    @Query("""
            SELECT COUNT(c) FROM Comment c
            WHERE c.id IN (
              SELECT m.commentId FROM CommentMention m WHERE m.mentionedUserId = :userId
            )
            """)
    long countMentionsForUser(@Param("userId") Long userId);
}
