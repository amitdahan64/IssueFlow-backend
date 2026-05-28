package com.att.tdp.issueflow.workload;

import com.att.tdp.issueflow.audit.AuditLogRepository;
import com.att.tdp.issueflow.auth.TokenDenylistRepository;
import com.att.tdp.issueflow.auth.dto.LoginRequest;
import com.att.tdp.issueflow.common.domain.AuditAction;
import com.att.tdp.issueflow.common.domain.AuditActor;
import com.att.tdp.issueflow.common.domain.EntityType;
import com.att.tdp.issueflow.common.domain.Priority;
import com.att.tdp.issueflow.common.domain.Role;
import com.att.tdp.issueflow.common.domain.TicketStatus;
import com.att.tdp.issueflow.common.domain.TicketType;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WorkloadAndAutoAssignTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private AuditLogRepository auditRepository;
    @Autowired private TokenDenylistRepository denylistRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private User admin; // owner (ADMIN — non-DEVELOPER)
    private User devEarliest;
    private User devLater;
    private Project project;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        denylistRepository.deleteAll();
        auditRepository.deleteAll();
        ticketRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ---- helpers ----

    private User seedUser(String username, Role role, String password) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@example.com");
        u.setFullName(username);
        u.setRole(role);
        u.setPasswordHash(passwordEncoder.encode(password));
        return userRepository.saveAndFlush(u);
    }

    private Project seedProject(Long ownerId, String name) {
        Project p = new Project();
        p.setName(name);
        p.setDescription("desc");
        p.setOwnerId(ownerId);
        return projectRepository.saveAndFlush(p);
    }

    private Ticket seedTicket(Long projectId, Long assigneeId, TicketStatus status) {
        Ticket t = new Ticket();
        t.setTitle("seed");
        t.setStatus(status);
        t.setPriority(Priority.MEDIUM);
        t.setType(TicketType.BUG);
        t.setProjectId(projectId);
        t.setAssigneeId(assigneeId);
        return ticketRepository.saveAndFlush(t);
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

    private void seedStandardSetup() throws Exception {
        admin = seedUser("root", Role.ADMIN, "secret");
        devEarliest = seedUser("alice", Role.DEVELOPER, "secret");
        devLater = seedUser("bob", Role.DEVELOPER, "secret");
        project = seedProject(admin.getId(), "Alpha");
        token = login("root", "secret");
        auditRepository.deleteAll(); // discard LOGIN noise
    }

    private long postTicketWithoutAssignee() throws Exception {
        TicketCreateDto dto = new TicketCreateDto(
                "T-" + System.nanoTime(), "x",
                TicketStatus.TODO, Priority.LOW, TicketType.BUG,
                project.getId(), /*assigneeId=*/ null, /*dueDate=*/ null);
        MvcResult res = mockMvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .get("id").asLong();
    }

    private JsonNode getTicket(long id) throws Exception {
        MvcResult res = mockMvc.perform(get("/tickets/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    // ---- tests ----

    @Test
    void auto_assign_picks_least_loaded_developer_and_audits_AUTO_ASSIGN() throws Exception {
        seedStandardSetup();

        // alice has 2 open tickets, bob has 0
        seedTicket(project.getId(), devEarliest.getId(), TicketStatus.IN_PROGRESS);
        seedTicket(project.getId(), devEarliest.getId(), TicketStatus.IN_REVIEW);

        long ticketId = postTicketWithoutAssignee();

        assertThat(getTicket(ticketId).get("assigneeId").asLong()).isEqualTo(devLater.getId());

        assertThat(auditRepository.findAll()).anyMatch(r ->
                r.getAction() == AuditAction.AUTO_ASSIGN
                        && r.getEntityType() == EntityType.TICKET
                        && r.getEntityId() == ticketId
                        && r.getActor() == AuditActor.SYSTEM
                        && r.getPerformedBy() == null);
    }

    @Test
    void auto_assign_tie_breaks_by_oldest_registration() throws Exception {
        seedStandardSetup();
        // Both devs have 0 open tickets. alice is older → should win.

        long ticketId = postTicketWithoutAssignee();
        assertThat(getTicket(ticketId).get("assigneeId").asLong()).isEqualTo(devEarliest.getId());
    }

    @Test
    void auto_assign_falls_back_to_null_when_no_developers_exist() throws Exception {
        admin = seedUser("root", Role.ADMIN, "secret");
        project = seedProject(admin.getId(), "Alpha");
        token = login("root", "secret");
        auditRepository.deleteAll();

        long ticketId = postTicketWithoutAssignee();

        assertThat(getTicket(ticketId).get("assigneeId").isNull()).isTrue();
        // No AUTO_ASSIGN audit when nobody was picked
        assertThat(auditRepository.findAll())
                .noneMatch(r -> r.getAction() == AuditAction.AUTO_ASSIGN);
    }

    @Test
    void DONE_tickets_do_not_count_toward_workload() throws Exception {
        seedStandardSetup();
        // alice has 5 DONE tickets, bob has 1 IN_PROGRESS
        for (int i = 0; i < 5; i++) {
            seedTicket(project.getId(), devEarliest.getId(), TicketStatus.DONE);
        }
        seedTicket(project.getId(), devLater.getId(), TicketStatus.IN_PROGRESS);

        long ticketId = postTicketWithoutAssignee();
        assertThat(getTicket(ticketId).get("assigneeId").asLong()).isEqualTo(devEarliest.getId());
    }

    @Test
    void soft_deleted_tickets_do_not_count_toward_workload() throws Exception {
        seedStandardSetup();
        // alice has 2 IN_PROGRESS but one is soft-deleted
        seedTicket(project.getId(), devEarliest.getId(), TicketStatus.IN_PROGRESS);
        Ticket dead = seedTicket(project.getId(), devEarliest.getId(), TicketStatus.IN_PROGRESS);
        dead.setDeletedAt(Instant.now());
        ticketRepository.saveAndFlush(dead);
        // bob has 1 IN_PROGRESS
        seedTicket(project.getId(), devLater.getId(), TicketStatus.IN_PROGRESS);

        // Effective open counts: alice=1, bob=1 → tie-break by createdAt → alice wins
        long ticketId = postTicketWithoutAssignee();
        assertThat(getTicket(ticketId).get("assigneeId").asLong()).isEqualTo(devEarliest.getId());
    }

    @Test
    void explicit_assigneeId_skips_auto_assign_and_does_not_write_AUTO_ASSIGN_audit() throws Exception {
        seedStandardSetup();

        TicketCreateDto dto = new TicketCreateDto(
                "T", "x", TicketStatus.TODO, Priority.LOW, TicketType.BUG,
                project.getId(), devLater.getId(), null);
        mockMvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").value(devLater.getId()));

        assertThat(auditRepository.findAll())
                .noneMatch(r -> r.getAction() == AuditAction.AUTO_ASSIGN);
    }

    @Test
    void auto_assign_does_not_fire_on_PATCH() throws Exception {
        seedStandardSetup();
        Ticket t = seedTicket(project.getId(), devEarliest.getId(), TicketStatus.TODO);
        auditRepository.deleteAll();

        TicketUpdateDto patch = new TicketUpdateDto(
                "renamed", null, null, null, null, null, t.getVersion());
        mockMvc.perform(patch("/tickets/" + t.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(patch)))
                .andExpect(status().isOk());

        assertThat(auditRepository.findAll())
                .noneMatch(r -> r.getAction() == AuditAction.AUTO_ASSIGN);
    }

    @Test
    void GET_workload_reflects_open_ticket_counts_and_sorts_ascending() throws Exception {
        seedStandardSetup();
        seedTicket(project.getId(), devEarliest.getId(), TicketStatus.IN_PROGRESS);
        seedTicket(project.getId(), devEarliest.getId(), TicketStatus.IN_REVIEW);
        // bob: 0 open

        MvcResult res = mockMvc.perform(get("/projects/" + project.getId() + "/workload")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode arr = objectMapper.readTree(res.getResponse().getContentAsString());
        // Project owner is the ADMIN root — included; alice (2), bob (0), root (0)
        assertThat(arr.size()).isEqualTo(3);
        // Sorted ASC by openTicketCount: bob/root tied at 0, alice at 2.
        assertThat(arr.get(0).get("openTicketCount").asLong()).isZero();
        assertThat(arr.get(1).get("openTicketCount").asLong()).isZero();
        assertThat(arr.get(2).get("openTicketCount").asLong()).isEqualTo(2);
        assertThat(arr.get(2).get("username").asText()).isEqualTo("alice");
    }

    @Test
    void GET_workload_excludes_DONE_and_soft_deleted_tickets_from_counts() throws Exception {
        seedStandardSetup();
        // alice: 1 IN_PROGRESS + 1 DONE + 1 soft-deleted IN_PROGRESS
        seedTicket(project.getId(), devEarliest.getId(), TicketStatus.IN_PROGRESS);
        seedTicket(project.getId(), devEarliest.getId(), TicketStatus.DONE);
        Ticket dead = seedTicket(project.getId(), devEarliest.getId(), TicketStatus.IN_PROGRESS);
        dead.setDeletedAt(Instant.now());
        ticketRepository.saveAndFlush(dead);

        MvcResult res = mockMvc.perform(get("/projects/" + project.getId() + "/workload")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode arr = objectMapper.readTree(res.getResponse().getContentAsString());

        long aliceCount = -1;
        for (int i = 0; i < arr.size(); i++) {
            if ("alice".equals(arr.get(i).get("username").asText())) {
                aliceCount = arr.get(i).get("openTicketCount").asLong();
                break;
            }
        }
        assertThat(aliceCount).isEqualTo(1);
    }

    @Test
    void GET_workload_404s_for_unknown_project() throws Exception {
        seedStandardSetup();
        mockMvc.perform(get("/projects/99999/workload")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
