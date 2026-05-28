package com.att.tdp.issueflow.e2e;

import com.att.tdp.issueflow.audit.AuditLogRepository;
import com.att.tdp.issueflow.auth.TokenDenylistRepository;
import com.att.tdp.issueflow.auth.dto.LoginRequest;
import com.att.tdp.issueflow.comment.CommentMentionRepository;
import com.att.tdp.issueflow.comment.CommentRepository;
import com.att.tdp.issueflow.comment.dto.CommentCreateDto;
import com.att.tdp.issueflow.common.domain.AuditAction;
import com.att.tdp.issueflow.common.domain.AuditActor;
import com.att.tdp.issueflow.common.domain.EntityType;
import com.att.tdp.issueflow.common.domain.Priority;
import com.att.tdp.issueflow.common.domain.Role;
import com.att.tdp.issueflow.common.domain.TicketStatus;
import com.att.tdp.issueflow.common.domain.TicketType;
import com.att.tdp.issueflow.dependency.TicketDependencyRepository;
import com.att.tdp.issueflow.dependency.dto.CreateDependencyDto;
import com.att.tdp.issueflow.escalation.EscalationService;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.project.dto.ProjectCreateDto;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketRepository;
import com.att.tdp.issueflow.ticket.dto.TicketCreateDto;
import com.att.tdp.issueflow.ticket.dto.TicketUpdateDto;
import com.att.tdp.issueflow.user.UserRepository;
import com.att.tdp.issueflow.user.dto.UserCreateDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Single end-to-end walkthrough of the IssueFlow surface — exercises every phase
 * and proves they compose. Not transactional: state persists across the steps so
 * subsequent steps can observe it (mirrors a real client conversation).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EndToEndFlowTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private CommentRepository commentRepository;
    @Autowired private CommentMentionRepository mentionRepository;
    @Autowired private TicketDependencyRepository dependencyRepository;
    @Autowired private AuditLogRepository auditRepository;
    @Autowired private TokenDenylistRepository denylistRepository;
    @Autowired private EscalationService escalationService;

    @BeforeEach
    void clean() {
        denylistRepository.deleteAll();
        auditRepository.deleteAll();
        mentionRepository.deleteAll();
        commentRepository.deleteAll();
        dependencyRepository.deleteAll();
        ticketRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void full_flow_login_to_export_import_to_soft_delete_and_restore() throws Exception {
        // ──── Phase 1/2 — registration + JWT auth ────────────────────────────────
        registerUser("root",  "root@example.com",  "Root Admin",  Role.ADMIN,     "secret1");
        long aliceId = registerUser("alice", "alice@example.com", "Alice Smith", Role.DEVELOPER, "secret2");
        long bobId   = registerUser("bob",   "bob@example.com",   "Bob Brown",   Role.DEVELOPER, "secret3");

        String adminToken = login("root",  "secret1");
        String aliceToken = login("alice", "secret2");

        // /auth/me works for the authenticated user
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());

        // ──── Phase 4 — project CRUD ─────────────────────────────────────────────
        long projectId = createProject("Alpha", "Demo project", aliceId, aliceToken);

        // ──── Phase 5 + 8 — ticket create with auto-assignment ───────────────────
        // No explicit assigneeId → auto-assign picks the oldest DEVELOPER (alice).
        long autoAssignedTicket = createTicketWithoutAssignee(projectId, "Auto-assigned", "...", aliceToken);
        JsonNode autoTicket = getTicket(autoAssignedTicket, aliceToken);
        assertThat(autoTicket.get("assigneeId").asLong()).isEqualTo(aliceId);
        assertThat(auditOfType(AuditAction.AUTO_ASSIGN, EntityType.TICKET, autoAssignedTicket))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.getActor()).isEqualTo(AuditActor.SYSTEM);
                    assertThat(r.getPerformedBy()).isNull();
                });

        // Explicit-assignee ticket — won't trigger AUTO_ASSIGN.
        long manualTicket = createTicketWithAssignee(projectId, "Manual", "...", bobId, aliceToken);

        // ──── Phase 5 — lifecycle enforcement ────────────────────────────────────
        // Walk autoAssignedTicket TODO → IN_PROGRESS → IN_REVIEW, then try DONE.
        patchTicketStatus(autoAssignedTicket, TicketStatus.IN_PROGRESS, 0L, aliceToken, status().isOk());
        patchTicketStatus(autoAssignedTicket, TicketStatus.IN_REVIEW,   1L, aliceToken, status().isOk());

        // Backward transition rejected
        patchTicketStatus(autoAssignedTicket, TicketStatus.TODO, 2L, aliceToken, status().isBadRequest());

        // ──── Phase 6 — dependency blocks DONE transition ────────────────────────
        addDependency(autoAssignedTicket, manualTicket, aliceToken); // autoAssigned blocked by manualTicket

        // manualTicket is TODO; DONE on autoAssignedTicket must be blocked.
        patchTicketStatus(autoAssignedTicket, TicketStatus.DONE, 2L, aliceToken, status().isBadRequest());

        // Move blocker through to DONE
        patchTicketStatus(manualTicket, TicketStatus.IN_PROGRESS, 0L, aliceToken, status().isOk());
        patchTicketStatus(manualTicket, TicketStatus.IN_REVIEW,   1L, aliceToken, status().isOk());
        patchTicketStatus(manualTicket, TicketStatus.DONE,        2L, aliceToken, status().isOk());

        // Now autoAssignedTicket can go DONE
        patchTicketStatus(autoAssignedTicket, TicketStatus.DONE, 2L, aliceToken, status().isOk());

        // ──── Phase 7 — comments + mentions ──────────────────────────────────────
        long commentId = postComment(manualTicket, aliceId, "fyi @bob ship is sailing", aliceToken);
        JsonNode commentList = getJson("/tickets/" + manualTicket + "/comments", aliceToken);
        assertThat(commentList.size()).isEqualTo(1);
        assertThat(commentList.get(0).get("mentionedUsers").get(0).get("id").asLong()).isEqualTo(bobId);

        // bob's mentions endpoint shows the row
        JsonNode bobMentions = getJson("/users/" + bobId + "/mentions", aliceToken);
        assertThat(bobMentions.get("total").asLong()).isEqualTo(1);

        // ──── Phase 9 — auto-escalation ──────────────────────────────────────────
        // Seed a new ticket with a past dueDate and LOW priority, then run one tick.
        long escalateId = createTicketWithoutAssignee(projectId, "Overdue", "...", aliceToken);
        // Backdate dueDate directly via the repository (no API for this) and clear isOverdue.
        Ticket t = ticketRepository.findById(escalateId).orElseThrow();
        t.setDueDate(Instant.now().minus(1, ChronoUnit.HOURS));
        t.setPriority(Priority.LOW);
        ticketRepository.saveAndFlush(t);

        escalationService.runOnce();

        Ticket afterTick = ticketRepository.findById(escalateId).orElseThrow();
        assertThat(afterTick.getPriority()).isEqualTo(Priority.MEDIUM);
        assertThat(auditOfType(AuditAction.AUTO_ESCALATE, EntityType.TICKET, escalateId))
                .hasSize(1);

        // ──── Phase 3 — audit log query ──────────────────────────────────────────
        JsonNode userCreateAudits = getJson(
                "/audit-logs?entityType=USER&action=CREATE", aliceToken);
        assertThat(userCreateAudits.size()).isEqualTo(3); // root, alice, bob

        // ──── Phase 10 — attachment upload ───────────────────────────────────────
        MockMultipartFile png = new MockMultipartFile(
                "file", "evidence.png", "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        mockMvc.perform(multipart("/tickets/" + manualTicket + "/attachments")
                        .file(png)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());

        // ──── Phase 11 — CSV export + import into another project ────────────────
        long secondProjectId = createProject("Beta", "Migration target", aliceId, aliceToken);

        byte[] csv = mockMvc.perform(get("/tickets/export")
                        .param("projectId", String.valueOf(projectId))
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        MvcResult importRes = mockMvc.perform(multipart("/tickets/import")
                        .file(new MockMultipartFile("file", "out.csv", "text/csv", csv))
                        .param("projectId", String.valueOf(secondProjectId))
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode importJson = objectMapper.readTree(importRes.getResponse().getContentAsString());
        // Source has at least the 3 tickets we created
        assertThat(importJson.get("created").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(importJson.get("failed").asInt()).isZero();

        // ──── Phase 4 / 5 — soft delete + ADMIN-only restore ─────────────────────
        mockMvc.perform(delete("/tickets/" + manualTicket)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());

        // DEVELOPER can't see the deleted-list
        mockMvc.perform(get("/tickets/deleted")
                        .param("projectId", String.valueOf(projectId))
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isForbidden());

        // ADMIN can; restore brings it back
        mockMvc.perform(get("/tickets/deleted")
                        .param("projectId", String.valueOf(projectId))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/tickets/" + manualTicket + "/restore")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/tickets/" + manualTicket)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());

        // ──── Phase 2 — logout denylists the token ───────────────────────────────
        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isUnauthorized());

        // Sanity: commentId not orphaned by the delete/restore round trip
        assertThat(commentRepository.findById(commentId)).isPresent();
    }

    // ─────────────────────────── helpers ────────────────────────────────────────

    private long registerUser(String username, String email, String fullName, Role role,
                              String password) throws Exception {
        MvcResult res = mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UserCreateDto(username, email, fullName, role, password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
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

    private long createProject(String name, String desc, long ownerId, String token) throws Exception {
        MvcResult res = mockMvc.perform(post("/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ProjectCreateDto(name, desc, ownerId))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private long createTicketWithoutAssignee(long projectId, String title, String desc,
                                             String token) throws Exception {
        return postTicket(new TicketCreateDto(title, desc, TicketStatus.TODO,
                Priority.MEDIUM, TicketType.BUG, projectId, null, null), token);
    }

    private long createTicketWithAssignee(long projectId, String title, String desc,
                                          long assigneeId, String token) throws Exception {
        return postTicket(new TicketCreateDto(title, desc, TicketStatus.TODO,
                Priority.MEDIUM, TicketType.BUG, projectId, assigneeId, null), token);
    }

    private long postTicket(TicketCreateDto dto, String token) throws Exception {
        MvcResult res = mockMvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private JsonNode getTicket(long id, String token) throws Exception {
        return getJson("/tickets/" + id, token);
    }

    private JsonNode getJson(String path, String token) throws Exception {
        MvcResult res = mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private void patchTicketStatus(long ticketId, TicketStatus to, long version, String token,
                                   org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        mockMvc.perform(patch("/tickets/" + ticketId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TicketUpdateDto(
                                null, null, to, null, null, null, version))))
                .andExpect(expected);
    }

    private void addDependency(long ticketId, long blockerId, String token) throws Exception {
        mockMvc.perform(post("/tickets/" + ticketId + "/dependencies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDependencyDto(blockerId))))
                .andExpect(status().isOk());
    }

    private long postComment(long ticketId, long authorId, String content, String token) throws Exception {
        MvcResult res = mockMvc.perform(post("/tickets/" + ticketId + "/comments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CommentCreateDto(authorId, content))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private List<com.att.tdp.issueflow.audit.AuditLog> auditOfType(AuditAction action, EntityType type, long entityId) {
        return auditRepository.findAll().stream()
                .filter(r -> r.getAction() == action
                        && r.getEntityType() == type
                        && r.getEntityId() == entityId)
                .toList();
    }

    @Test
    void user_registration_creates_audit_row_audit_log_endpoint_can_be_queried() throws Exception {
        // Cross-cutting check (per Phase 3 plan): every state-change endpoint shipped so far
        // produces an audit row queryable via GET /audit-logs.
        long aliceId = registerUser("alice", "alice@example.com", "Alice", Role.DEVELOPER, "secret1");
        String token = login("alice", "secret1");

        JsonNode logs = getJson(
                "/audit-logs?entityType=USER&action=CREATE&entityId=" + aliceId, token);
        assertThat(logs.size()).isEqualTo(1);
        assertThat(logs.get(0).get("performedBy").isNull()).isTrue(); // public POST /users → SYSTEM actor
        assertThat(logs.get(0).get("actor").asText()).isEqualTo("SYSTEM");
    }
}
