package com.att.tdp.issueflow.dependency;

import com.att.tdp.issueflow.audit.AuditLogRepository;
import com.att.tdp.issueflow.auth.TokenDenylistRepository;
import com.att.tdp.issueflow.auth.dto.LoginRequest;
import com.att.tdp.issueflow.common.domain.AuditAction;
import com.att.tdp.issueflow.common.domain.EntityType;
import com.att.tdp.issueflow.common.domain.Priority;
import com.att.tdp.issueflow.common.domain.Role;
import com.att.tdp.issueflow.common.domain.TicketStatus;
import com.att.tdp.issueflow.common.domain.TicketType;
import com.att.tdp.issueflow.dependency.dto.CreateDependencyDto;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketRepository;
import com.att.tdp.issueflow.ticket.dto.TicketUpdateDto;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
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
class TicketDependencyControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private TicketDependencyRepository depRepository;
    @Autowired private AuditLogRepository auditRepository;
    @Autowired private TokenDenylistRepository denylistRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private User developer;
    private Project project;
    private Project otherProject;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        denylistRepository.deleteAll();
        auditRepository.deleteAll();
        depRepository.deleteAll();
        ticketRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();

        developer = seedUser("dev1", "secret1");
        token = login("dev1", "secret1");

        project = seedProject("Alpha");
        otherProject = seedProject("Beta");

        auditRepository.deleteAll();
    }

    private User seedUser(String username, String password) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@example.com");
        u.setFullName(username);
        u.setRole(Role.DEVELOPER);
        u.setPasswordHash(passwordEncoder.encode(password));
        return userRepository.saveAndFlush(u);
    }

    private Project seedProject(String name) {
        Project p = new Project();
        p.setName(name);
        p.setDescription("desc");
        p.setOwnerId(developer.getId());
        return projectRepository.saveAndFlush(p);
    }

    private Ticket seedTicket(Project p, TicketStatus status) {
        Ticket t = new Ticket();
        t.setTitle("Ticket-" + System.nanoTime());
        t.setDescription("desc");
        t.setStatus(status);
        t.setPriority(Priority.MEDIUM);
        t.setType(TicketType.BUG);
        t.setProjectId(p.getId());
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

    @Test
    void POST_dependency_adds_blocker() throws Exception {
        Ticket t = seedTicket(project, TicketStatus.TODO);
        Ticket blocker = seedTicket(project, TicketStatus.IN_PROGRESS);

        mockMvc.perform(post("/tickets/" + t.getId() + "/dependencies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDependencyDto(blocker.getId()))))
                .andExpect(status().isOk());

        assertThat(depRepository.findAllByTicketId(t.getId()))
                .singleElement()
                .satisfies(d -> assertThat(d.getBlockerTicketId()).isEqualTo(blocker.getId()));

        assertThat(auditRepository.findAll()).anyMatch(r ->
                r.getAction() == AuditAction.CREATE && r.getEntityType() == EntityType.DEPENDENCY);
    }

    @Test
    void POST_dependency_400s_when_cross_project() throws Exception {
        Ticket t = seedTicket(project, TicketStatus.TODO);
        Ticket alien = seedTicket(otherProject, TicketStatus.TODO);

        mockMvc.perform(post("/tickets/" + t.getId() + "/dependencies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDependencyDto(alien.getId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("same project")));
    }

    @Test
    void POST_dependency_400s_on_self_block() throws Exception {
        Ticket t = seedTicket(project, TicketStatus.TODO);
        mockMvc.perform(post("/tickets/" + t.getId() + "/dependencies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDependencyDto(t.getId()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_dependency_409s_on_duplicate() throws Exception {
        Ticket t = seedTicket(project, TicketStatus.TODO);
        Ticket b = seedTicket(project, TicketStatus.TODO);
        String body = objectMapper.writeValueAsString(new CreateDependencyDto(b.getId()));

        mockMvc.perform(post("/tickets/" + t.getId() + "/dependencies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post("/tickets/" + t.getId() + "/dependencies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void POST_dependency_404s_when_blocker_missing() throws Exception {
        Ticket t = seedTicket(project, TicketStatus.TODO);
        mockMvc.perform(post("/tickets/" + t.getId() + "/dependencies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDependencyDto(99999L))))
                .andExpect(status().isNotFound());
    }

    @Test
    void GET_dependencies_returns_blocker_summaries() throws Exception {
        Ticket t = seedTicket(project, TicketStatus.TODO);
        Ticket b1 = seedTicket(project, TicketStatus.IN_PROGRESS);
        Ticket b2 = seedTicket(project, TicketStatus.IN_REVIEW);
        addDependency(t.getId(), b1.getId());
        addDependency(t.getId(), b2.getId());

        mockMvc.perform(get("/tickets/" + t.getId() + "/dependencies")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].id").value(org.hamcrest.Matchers.hasItems(b1.getId().intValue(), b2.getId().intValue())))
                .andExpect(jsonPath("$[*].status").value(org.hamcrest.Matchers.hasItems("IN_PROGRESS", "IN_REVIEW")));
    }

    @Test
    void DELETE_dependency_removes_and_audits() throws Exception {
        Ticket t = seedTicket(project, TicketStatus.TODO);
        Ticket b = seedTicket(project, TicketStatus.TODO);
        addDependency(t.getId(), b.getId());

        mockMvc.perform(delete("/tickets/" + t.getId() + "/dependencies/" + b.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(depRepository.findAllByTicketId(t.getId())).isEmpty();
        assertThat(auditRepository.findAll()).anyMatch(r ->
                r.getAction() == AuditAction.DELETE && r.getEntityType() == EntityType.DEPENDENCY);
    }

    @Test
    void DELETE_dependency_404s_when_missing() throws Exception {
        Ticket t = seedTicket(project, TicketStatus.TODO);
        Ticket b = seedTicket(project, TicketStatus.TODO);
        mockMvc.perform(delete("/tickets/" + t.getId() + "/dependencies/" + b.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void PATCH_to_DONE_is_blocked_until_blocker_is_DONE() throws Exception {
        Ticket t = seedTicket(project, TicketStatus.IN_REVIEW);
        Ticket blocker = seedTicket(project, TicketStatus.IN_PROGRESS);
        addDependency(t.getId(), blocker.getId());

        // Blocker not DONE → cannot move t to DONE
        long versionT = t.getVersion();
        mockMvc.perform(patch("/tickets/" + t.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TicketUpdateDto(
                                null, null, TicketStatus.DONE, null, null, null, versionT))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("blocker")));

        // Move blocker through to DONE: IN_PROGRESS → IN_REVIEW → DONE.
        // saveAndFlush updates the managed entity's @Version in place, so re-reading
        // blocker.getVersion() each call picks up the new value.
        moveStatus(blocker.getId(), blocker.getVersion(), TicketStatus.IN_REVIEW);
        moveStatus(blocker.getId(), blocker.getVersion(), TicketStatus.DONE);

        // Now t can be moved to DONE
        mockMvc.perform(patch("/tickets/" + t.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TicketUpdateDto(
                                null, null, TicketStatus.DONE, null, null, null, versionT))))
                .andExpect(status().isOk());
    }

    @Test
    void PATCH_to_IN_PROGRESS_is_not_blocked_by_dependencies() throws Exception {
        Ticket t = seedTicket(project, TicketStatus.TODO);
        Ticket blocker = seedTicket(project, TicketStatus.IN_PROGRESS);
        addDependency(t.getId(), blocker.getId());

        // DependencyBlockerGuard only kicks in on transitions to DONE.
        mockMvc.perform(patch("/tickets/" + t.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TicketUpdateDto(
                                null, null, TicketStatus.IN_PROGRESS, null, null, null, t.getVersion()))))
                .andExpect(status().isOk());
    }

    @Test
    void dependency_endpoints_require_authentication() throws Exception {
        Ticket t = seedTicket(project, TicketStatus.TODO);
        mockMvc.perform(get("/tickets/" + t.getId() + "/dependencies"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/tickets/" + t.getId() + "/dependencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blockedBy\": 1}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/tickets/" + t.getId() + "/dependencies/1"))
                .andExpect(status().isUnauthorized());
    }

    private void addDependency(Long ticketId, Long blockerId) throws Exception {
        mockMvc.perform(post("/tickets/" + ticketId + "/dependencies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDependencyDto(blockerId))))
                .andExpect(status().isOk());
    }

    private void moveStatus(Long ticketId, long version, TicketStatus to) throws Exception {
        mockMvc.perform(patch("/tickets/" + ticketId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TicketUpdateDto(
                                null, null, to, null, null, null, version))))
                .andExpect(status().isOk());
    }
}
