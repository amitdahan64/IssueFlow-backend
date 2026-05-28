package com.att.tdp.issueflow.comment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommentMentionRepository extends JpaRepository<CommentMention, Long> {

    List<CommentMention> findAllByCommentId(Long commentId);

    List<CommentMention> findAllByCommentIdIn(java.util.Collection<Long> commentIds);

    void deleteByCommentId(Long commentId);
}
