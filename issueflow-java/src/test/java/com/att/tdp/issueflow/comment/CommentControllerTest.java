package com.att.tdp.issueflow.comment;

import com.att.tdp.issueflow.audit.AuditLogRepository;
import com.att.tdp.issueflow.auth.TokenDenylistRepository;
import com.att.tdp.issueflow.auth.dto.LoginRequest;
import com.att.tdp.issueflow.comment.dto.CommentCreateDto;
import com.att.tdp.issueflow.comment.dto.CommentUpdateDto;
import com.att.tdp.issueflow.common.domain.AuditAction;
import com.att.tdp.issueflow.common.domain.EntityType;
import com.att.tdp.issueflow.common.domain.Priority;
import com.att.tdp.issueflow.common.domain.Role;
import com.att.tdp.issueflow.common.domain.TicketStatus;
import com.att.tdp.issueflow.common.domain.TicketType;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketRepository;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CommentControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private CommentRepository commentRepository;
    @Autowired private CommentMentionRepository mentionRepository;
    @Autowired private AuditLogRepository auditRepository;
    @Autowired private TokenDenylistRepository denylistRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private User alice;
    private User bob;
    private Ticket ticket;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        denylistRepository.deleteAll();
        auditRepository.deleteAll();
        mentionRepository.deleteAll();
        commentRepository.deleteAll();
        ticketRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();

        alice = seedUser("alice", "secret1");
        bob   = seedUser("bob", "secret2");
        token = login("alice", "secret1");

        Project p = new Project();
        p.setName("Alpha");
        p.setDescription("d");
        p.setOwnerId(alice.getId());
        p = projectRepository.saveAndFlush(p);

        Ticket t = new Ticket();
        t.setTitle("T");
        t.setStatus(TicketStatus.TODO);
        t.setPriority(Priority.LOW);
        t.setType(TicketType.BUG);
        t.setProjectId(p.getId());
        ticket = ticketRepository.saveAndFlush(t);

        auditRepository.deleteAll();
    }

    private User seedUser(String username, String password) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@example.com");
        u.setFullName(Character.toUpperCase(username.charAt(0)) + username.substring(1) + " User");
        u.setRole(Role.DEVELOPER);
        u.setPasswordHash(passwordEncoder.encode(password));
        return userRepository.saveAndFlush(u);
    }

    private String login(String username, String password) throws Exception {
        MvcResult res = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private long postComment(String content, Long authorId) throws Exception {
        MvcResult res = mockMvc.perform(post("/tickets/" + ticket.getId() + "/comments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CommentCreateDto(authorId, content))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void POST_comment_creates_and_returns_mentions() throws Exception {
        mockMvc.perform(post("/tickets/" + ticket.getId() + "/comments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CommentCreateDto(
                                alice.getId(), "Hello @bob!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.ticketId").value(ticket.getId()))
                .andExpect(jsonPath("$.authorId").value(alice.getId()))
                .andExpect(jsonPath("$.content").value("Hello @bob!"))
                .andExpect(jsonPath("$.mentionedUsers[0].id").value(bob.getId()))
                .andExpect(jsonPath("$.mentionedUsers[0].username").value("bob"))
                .andExpect(jsonPath("$.mentionedUsers[0].fullName").value("Bob User"))
                .andExpect(jsonPath("$.version").value(0));

        assertThat(auditRepository.findAll()).anyMatch(r ->
                r.getAction() == AuditAction.CREATE && r.getEntityType() == EntityType.COMMENT);
    }

    @Test
    void POST_comment_matches_mentions_case_insensitively() throws Exception {
        mockMvc.perform(post("/tickets/" + ticket.getId() + "/comments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CommentCreateDto(
                                alice.getId(), "ping @BOB"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mentionedUsers[0].id").value(bob.getId()));
    }

    @Test
    void POST_comment_skips_unknown_usernames_silently() throws Exception {
        mockMvc.perform(post("/tickets/" + ticket.getId() + "/comments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CommentCreateDto(
                                alice.getId(), "@nobody seen this?"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mentionedUsers.length()").value(0));
    }

    @Test
    void POST_comment_400s_on_blank_content() throws Exception {
        mockMvc.perform(post("/tickets/" + ticket.getId() + "/comments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorId\":" + alice.getId() + ",\"content\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_comment_404s_when_ticket_missing() throws Exception {
        mockMvc.perform(post("/tickets/99999/comments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CommentCreateDto(
                                alice.getId(), "Hi"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void POST_comment_404s_when_author_missing() throws Exception {
        mockMvc.perform(post("/tickets/" + ticket.getId() + "/comments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CommentCreateDto(99999L, "Hi"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void GET_comments_returns_list_ordered_by_creation() throws Exception {
        postComment("first", alice.getId());
        postComment("second", alice.getId());

        mockMvc.perform(get("/tickets/" + ticket.getId() + "/comments")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].content").value("first"))
                .andExpect(jsonPath("$[1].content").value("second"));
    }

    @Test
    void PATCH_comment_updates_content_bumps_version_and_revises_mentions() throws Exception {
        long id = postComment("Hi @bob", alice.getId());

        // Mention bob initially
        assertThat(mentionRepository.findAllByCommentId(id))
                .singleElement().satisfies(m ->
                        assertThat(m.getMentionedUserId()).isEqualTo(bob.getId()));

        // Update to mention alice instead
        mockMvc.perform(patch("/tickets/" + ticket.getId() + "/comments/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CommentUpdateDto(
                                "Hi @alice now", 0L))))
                .andExpect(status().isOk());

        assertThat(mentionRepository.findAllByCommentId(id))
                .singleElement().satisfies(m ->
                        assertThat(m.getMentionedUserId()).isEqualTo(alice.getId()));

        // Re-fetch and verify version & content
        MvcResult res = mockMvc.perform(get("/tickets/" + ticket.getId() + "/comments")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode arr = objectMapper.readTree(res.getResponse().getContentAsString());
        assertThat(arr.get(0).get("content").asText()).isEqualTo("Hi @alice now");
        assertThat(arr.get(0).get("version").asLong()).isEqualTo(1L);
    }

    @Test
    void PATCH_comment_409s_on_stale_version() throws Exception {
        long id = postComment("Hi", alice.getId());

        // First PATCH bumps version to 1
        mockMvc.perform(patch("/tickets/" + ticket.getId() + "/comments/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CommentUpdateDto("Updated", 0L))))
                .andExpect(status().isOk());

        // Second PATCH still claims version=0 — stale.
        mockMvc.perform(patch("/tickets/" + ticket.getId() + "/comments/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CommentUpdateDto("Conflict", 0L))))
                .andExpect(status().isConflict());
    }

    @Test
    void DELETE_comment_removes_it_and_its_mentions() throws Exception {
        long id = postComment("Hi @bob", alice.getId());
        assertThat(mentionRepository.findAllByCommentId(id)).hasSize(1);

        mockMvc.perform(delete("/tickets/" + ticket.getId() + "/comments/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(commentRepository.findById(id)).isEmpty();
        assertThat(mentionRepository.findAllByCommentId(id)).isEmpty();
        assertThat(auditRepository.findAll()).anyMatch(r ->
                r.getAction() == AuditAction.DELETE && r.getEntityType() == EntityType.COMMENT);
    }

    @Test
    void DELETE_comment_404s_when_missing() throws Exception {
        mockMvc.perform(delete("/tickets/" + ticket.getId() + "/comments/99999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void GET_user_mentions_returns_paginated_newest_first() throws Exception {
        postComment("ping @bob 1", alice.getId());
        postComment("ping @bob 2", alice.getId());
        postComment("ping @bob 3", alice.getId());

        mockMvc.perform(get("/users/" + bob.getId() + "/mentions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.data.length()").value(3))
                // newest first
                .andExpect(jsonPath("$.data[0].content").value("ping @bob 3"))
                .andExpect(jsonPath("$.data[2].content").value("ping @bob 1"));
    }

    @Test
    void GET_user_mentions_respects_pageSize() throws Exception {
        postComment("ping @bob a", alice.getId());
        postComment("ping @bob b", alice.getId());
        postComment("ping @bob c", alice.getId());

        mockMvc.perform(get("/users/" + bob.getId() + "/mentions")
                        .param("page", "1")
                        .param("pageSize", "2")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void GET_user_mentions_empty_for_user_never_mentioned() throws Exception {
        // alice has not been mentioned
        mockMvc.perform(get("/users/" + alice.getId() + "/mentions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void GET_user_mentions_404s_for_unknown_user() throws Exception {
        mockMvc.perform(get("/users/99999/mentions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void comment_endpoints_require_authentication() throws Exception {
        mockMvc.perform(get("/tickets/" + ticket.getId() + "/comments"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/tickets/" + ticket.getId() + "/comments")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/tickets/" + ticket.getId() + "/comments/1"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/users/" + bob.getId() + "/mentions"))
                .andExpect(status().isUnauthorized());
    }
}
