package com.att.tdp.issueflow.comment;

import com.att.tdp.issueflow.comment.dto.CommentCreateDto;
import com.att.tdp.issueflow.comment.dto.CommentResponseDto;
import com.att.tdp.issueflow.comment.dto.CommentUpdateDto;
import com.att.tdp.issueflow.comment.dto.MentionedUserDto;
import com.att.tdp.issueflow.comment.dto.MentionsPageDto;
import com.att.tdp.issueflow.common.audit.AuditService;
import com.att.tdp.issueflow.common.domain.AuditAction;
import com.att.tdp.issueflow.common.domain.EntityType;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.ticket.TicketService;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentRepository commentRepository;
    private final CommentMentionRepository mentionRepository;
    private final UserRepository userRepository;
    private final TicketService ticketService;
    private final MentionParser mentionParser;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<CommentResponseDto> listForTicket(Long ticketId) {
        ticketService.findById(ticketId);
        List<Comment> comments = commentRepository.findAllByTicketIdOrderByCreatedAtAsc(ticketId);
        return hydrate(comments);
    }

    @Transactional
    public CommentResponseDto create(Long ticketId, CommentCreateDto dto) {
        ticketService.findById(ticketId);
        if (!userRepository.existsById(dto.authorId())) {
            throw NotFoundException.of("User", dto.authorId());
        }

        Comment comment = new Comment();
        comment.setTicketId(ticketId);
        comment.setAuthorId(dto.authorId());
        comment.setContent(dto.content());
        Comment saved = commentRepository.save(comment);

        Set<Long> mentionedIds = mentionParser.extractMentionedUserIds(dto.content());
        persistMentions(saved.getId(), mentionedIds);

        auditService.log(AuditAction.CREATE, EntityType.COMMENT, saved.getId());
        return hydrate(List.of(saved)).get(0);
    }

    @Transactional
    public void update(Long ticketId, Long commentId, CommentUpdateDto dto) {
        Comment existing = loadCommentOnTicket(ticketId, commentId);

        if (!dto.version().equals(existing.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(
                    "Comment " + commentId + " was modified concurrently (expected version "
                            + dto.version() + ", actual " + existing.getVersion() + ").",
                    Comment.class);
        }

        existing.setContent(dto.content());
        // saveAndFlush so the @Version bump materializes before the mention diff
        commentRepository.saveAndFlush(existing);

        // Re-evaluate mentions: insert new, delete removed.
        Set<Long> newSet = mentionParser.extractMentionedUserIds(dto.content());
        Set<Long> oldSet = mentionRepository.findAllByCommentId(commentId).stream()
                .map(CommentMention::getMentionedUserId)
                .collect(Collectors.toCollection(HashSet::new));

        Set<Long> toAdd = new HashSet<>(newSet);
        toAdd.removeAll(oldSet);
        Set<Long> toRemove = new HashSet<>(oldSet);
        toRemove.removeAll(newSet);

        persistMentions(commentId, toAdd);
        if (!toRemove.isEmpty()) {
            mentionRepository.findAllByCommentId(commentId).stream()
                    .filter(m -> toRemove.contains(m.getMentionedUserId()))
                    .forEach(mentionRepository::delete);
        }

        auditService.log(AuditAction.UPDATE, EntityType.COMMENT, commentId);
    }

    @Transactional
    public void delete(Long ticketId, Long commentId) {
        Comment existing = loadCommentOnTicket(ticketId, commentId);
        mentionRepository.deleteByCommentId(commentId);
        commentRepository.delete(existing);
        auditService.log(AuditAction.DELETE, EntityType.COMMENT, commentId);
    }

    @Transactional(readOnly = true)
    public MentionsPageDto listMentionsForUser(Long userId, int page, int pageSize) {
        if (!userRepository.existsById(userId)) {
            throw NotFoundException.of("User", userId);
        }
        int zeroBasedPage = Math.max(0, page - 1);
        List<Comment> mentioned = commentRepository.findMentionsForUser(
                userId, PageRequest.of(zeroBasedPage, pageSize));
        long total = commentRepository.countMentionsForUser(userId);
        return new MentionsPageDto(hydrate(mentioned), total, page);
    }

    // ---- helpers ----

    private Comment loadCommentOnTicket(Long ticketId, Long commentId) {
        // Verify ticket exists (and not soft-deleted) so /tickets/{bogus}/comments/{any}
        // returns a meaningful 404.
        ticketService.findById(ticketId);
        Comment existing = commentRepository.findById(commentId)
                .orElseThrow(() -> NotFoundException.of("Comment", commentId));
        if (!existing.getTicketId().equals(ticketId)) {
            throw NotFoundException.of("Comment", commentId);
        }
        return existing;
    }

    private void persistMentions(Long commentId, Set<Long> userIds) {
        for (Long uid : userIds) {
            CommentMention m = new CommentMention();
            m.setCommentId(commentId);
            m.setMentionedUserId(uid);
            mentionRepository.save(m);
        }
    }

    private List<CommentResponseDto> hydrate(List<Comment> comments) {
        if (comments.isEmpty()) return List.of();

        // Bulk-load mentions for all comments in one query.
        List<Long> commentIds = comments.stream().map(Comment::getId).toList();
        Map<Long, List<Long>> mentionsByComment = mentionRepository.findAllByCommentIdIn(commentIds).stream()
                .collect(Collectors.groupingBy(
                        CommentMention::getCommentId,
                        Collectors.mapping(CommentMention::getMentionedUserId, Collectors.toList())));

        Set<Long> allUserIds = mentionsByComment.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toSet());
        Map<Long, User> usersById = userRepository.findAllById(allUserIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        return comments.stream().map(c -> {
            List<MentionedUserDto> mentioned = mentionsByComment
                    .getOrDefault(c.getId(), List.of()).stream()
                    .map(usersById::get)
                    .filter(java.util.Objects::nonNull)
                    .map(MentionedUserDto::from)
                    .toList();
            return CommentResponseDto.from(c, mentioned);
        }).toList();
    }
}
