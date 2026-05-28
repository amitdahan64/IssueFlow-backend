package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.audit.AuditLogRepository;
import com.att.tdp.issueflow.auth.TokenDenylistRepository;
import com.att.tdp.issueflow.auth.dto.LoginRequest;
import com.att.tdp.issueflow.common.domain.Priority;
import com.att.tdp.issueflow.common.domain.Role;
import com.att.tdp.issueflow.common.domain.TicketStatus;
import com.att.tdp.issueflow.common.domain.TicketType;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.dto.TicketCreateDto;
import com.att.tdp.issueflow.ticket.dto.TicketUpdateDto;
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

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TicketControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private AuditLogRepository auditRepository;
    @Autowired private TokenDenylistRepository denylistRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private User developer;
    private Project project;
    private String developerToken;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        denylistRepository.deleteAll();
        auditRepository.deleteAll();
        ticketRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();

        developer = seedUser("dev1", Role.DEVELOPER, "secret1");
        seedUser("root", Role.ADMIN, "secret2");
        developerToken = login("dev1", "secret1");
        adminToken = login("root", "secret2");

        project = new Project();
        project.setName("Alpha");
        project.setDescription("Test");
        project.setOwnerId(developer.getId());
        project = projectRepository.saveAndFlush(project);

        auditRepository.deleteAll(); // discard LOGIN + setup noise
    }

    private User seedUser(String username, Role role, String password) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@example.com");
        u.setFullName(username);
        u.setRole(role);
        u.setPasswordHash(passwordEncoder.encode(password));
        return userRepository.saveAndFlush(u);
    }

    private String login(String username, String password) throws Exception {
        MvcResult res = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private TicketCreateDto baseCreate(TicketStatus status, Priority priority) {
        return new TicketCreateDto("Fix login bug", "...", status, priority, TicketType.BUG,
                project.getId(), developer.getId(), null);
    }

    private long createTicketRaw() throws Exception {
        MvcResult res = mockMvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(baseCreate(TicketStatus.TODO, Priority.MEDIUM))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private JsonNode getTicket(long id) throws Exception {
        MvcResult res = mockMvc.perform(get("/tickets/" + id)
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    @Test
    void POST_tickets_creates_with_version_0_and_supplied_assignee() throws Exception {
        mockMvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(baseCreate(TicketStatus.TODO, Priority.HIGH))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.status").value("TODO"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.assigneeId").value(developer.getId()))
                .andExpect(jsonPath("$.isOverdue").value(false))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void POST_tickets_rejects_bad_enum() throws Exception {
        String json = """
                { "title": "X", "description": "x", "status": "NOT_A_STATUS",
                  "priority": "HIGH", "type": "BUG", "projectId": %d }
                """.formatted(project.getId());
        mockMvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_tickets_404s_when_project_missing() throws Exception {
        TicketCreateDto dto = new TicketCreateDto("X", "x", TicketStatus.TODO, Priority.LOW,
                TicketType.BUG, 9999L, null, null);
        mockMvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isNotFound());
    }

    @Test
    void POST_tickets_404s_when_assignee_unknown() throws Exception {
        TicketCreateDto dto = new TicketCreateDto("X", "x", TicketStatus.TODO, Priority.LOW,
                TicketType.BUG, project.getId(), 9999L, null);
        mockMvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isNotFound());
    }

    @Test
    void PATCH_ticket_moves_status_forward() throws Exception {
        long id = createTicketRaw();
        TicketUpdateDto patch = new TicketUpdateDto(null, null, TicketStatus.IN_PROGRESS,
                null, null, null, 0L);

        mockMvc.perform(patch("/tickets/" + id)
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(patch)))
                .andExpect(status().isOk());

        JsonNode updated = getTicket(id);
        org.assertj.core.api.Assertions.assertThat(updated.get("status").asText()).isEqualTo("IN_PROGRESS");
        org.assertj.core.api.Assertions.assertThat(updated.get("version").asLong()).isEqualTo(1L);
    }

    @Test
    void PATCH_ticket_rejects_backward_status_transition() throws Exception {
        long id = createTicketRaw();
        // First move forward TODO -> IN_PROGRESS
        mockMvc.perform(patch("/tickets/" + id)
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TicketUpdateDto(
                                null, null, TicketStatus.IN_PROGRESS, null, null, null, 0L))))
                .andExpect(status().isOk());

        // Then try backward: IN_PROGRESS -> TODO
        mockMvc.perform(patch("/tickets/" + id)
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TicketUpdateDto(
                                null, null, TicketStatus.TODO, null, null, null, 1L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("forward-only")));
    }

    @Test
    void PATCH_ticket_rejects_edits_once_DONE() throws Exception {
        long id = createTicketRaw();
        // Walk to DONE: TODO -> IN_PROGRESS -> IN_REVIEW -> DONE
        long v = 0;
        for (TicketStatus next : new TicketStatus[]{TicketStatus.IN_PROGRESS, TicketStatus.IN_REVIEW, TicketStatus.DONE}) {
            mockMvc.perform(patch("/tickets/" + id)
                            .header("Authorization", "Bearer " + developerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new TicketUpdateDto(
                                    null, null, next, null, null, null, v))))
                    .andExpect(status().isOk());
            v++;
        }

        // Any further PATCH must be rejected.
        mockMvc.perform(patch("/tickets/" + id)
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TicketUpdateDto(
                                "New title", null, null, null, null, null, v))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("DONE")));
    }

    @Test
    void PATCH_ticket_409s_on_stale_version() throws Exception {
        long id = createTicketRaw();
        // First PATCH bumps version to 1
        mockMvc.perform(patch("/tickets/" + id)
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TicketUpdateDto(
                                "Updated", null, null, null, null, null, 0L))))
                .andExpect(status().isOk());

        // Second PATCH still claims version=0 — stale.
        mockMvc.perform(patch("/tickets/" + id)
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TicketUpdateDto(
                                "Conflict", null, null, null, null, null, 0L))))
                .andExpect(status().isConflict());
    }

    @Test
    void PATCH_ticket_clears_isOverdue_on_manual_priority_change() throws Exception {
        long id = createTicketRaw();
        // Simulate the scheduler having flagged it as overdue
        Ticket t = ticketRepository.findById(id).orElseThrow();
        t.setOverdue(true);
        ticketRepository.saveAndFlush(t);
        long currentVersion = t.getVersion();

        mockMvc.perform(patch("/tickets/" + id)
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TicketUpdateDto(
                                null, null, null, Priority.HIGH, null, null, currentVersion))))
                .andExpect(status().isOk());

        JsonNode updated = getTicket(id);
        org.assertj.core.api.Assertions.assertThat(updated.get("priority").asText()).isEqualTo("HIGH");
        org.assertj.core.api.Assertions.assertThat(updated.get("isOverdue").asBoolean()).isFalse();
    }

    @Test
    void PATCH_with_same_priority_does_not_reset_isOverdue() throws Exception {
        long id = createTicketRaw();
        Ticket t = ticketRepository.findById(id).orElseThrow();
        t.setOverdue(true);
        ticketRepository.saveAndFlush(t);
        long currentVersion = t.getVersion();

        // Patch with same priority (MEDIUM) — does not clear the flag.
        mockMvc.perform(patch("/tickets/" + id)
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TicketUpdateDto(
                                "tweak", null, null, Priority.MEDIUM, null, null, currentVersion))))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(getTicket(id).get("isOverdue").asBoolean()).isTrue();
    }

    @Test
    void GET_tickets_by_project_excludes_soft_deleted() throws Exception {
        long alive = createTicketRaw();
        long dead = createTicketRaw();
        mockMvc.perform(delete("/tickets/" + dead)
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk());

        MvcResult res = mockMvc.perform(get("/tickets")
                        .param("projectId", project.getId().toString())
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode arr = objectMapper.readTree(res.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(arr).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(arr.get(0).get("id").asLong()).isEqualTo(alive);
    }

    @Test
    void GET_ticket_by_id_returns_404_after_soft_delete() throws Exception {
        long id = createTicketRaw();
        mockMvc.perform(delete("/tickets/" + id)
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/tickets/" + id)
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void GET_deleted_requires_ADMIN_and_filters_by_project() throws Exception {
        long id = createTicketRaw();
        mockMvc.perform(delete("/tickets/" + id)
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk());

        // DEVELOPER forbidden
        mockMvc.perform(get("/tickets/deleted")
                        .param("projectId", project.getId().toString())
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isForbidden());

        // ADMIN sees the dead ticket
        mockMvc.perform(get("/tickets/deleted")
                        .param("projectId", project.getId().toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].title").exists());
    }

    @Test
    void POST_restore_requires_ADMIN_and_revives_the_ticket() throws Exception {
        long id = createTicketRaw();
        mockMvc.perform(delete("/tickets/" + id)
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk());

        // DEVELOPER forbidden
        mockMvc.perform(post("/tickets/" + id + "/restore")
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isForbidden());

        // ADMIN restores
        mockMvc.perform(post("/tickets/" + id + "/restore")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/tickets/" + id)
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk());
    }

    @Test
    void POST_restore_400s_when_not_deleted() throws Exception {
        long id = createTicketRaw();
        mockMvc.perform(post("/tickets/" + id + "/restore")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unauthenticated_ticket_endpoints_return_401() throws Exception {
        mockMvc.perform(get("/tickets").param("projectId", "1"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/tickets/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void PATCH_dueDate_round_trips() throws Exception {
        long id = createTicketRaw();
        Instant due = Instant.parse("2026-09-01T00:00:00Z");
        mockMvc.perform(patch("/tickets/" + id)
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TicketUpdateDto(
                                null, null, null, null, null, due, 0L))))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(
                getTicket(id).get("dueDate").asText()).isEqualTo("2026-09-01T00:00:00Z");
    }
}
