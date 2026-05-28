package com.att.tdp.issueflow.audit;

import com.att.tdp.issueflow.auth.TokenDenylistRepository;
import com.att.tdp.issueflow.auth.dto.LoginRequest;
import com.att.tdp.issueflow.common.domain.AuditAction;
import com.att.tdp.issueflow.common.domain.AuditActor;
import com.att.tdp.issueflow.common.domain.EntityType;
import com.att.tdp.issueflow.common.domain.Role;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import com.att.tdp.issueflow.user.dto.UserUpdateDto;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuditLogIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditRepo;
    @Autowired private TokenDenylistRepository denylistRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void clean() {
        denylistRepository.deleteAll();
        auditRepo.deleteAll();
        userRepository.deleteAll();
    }

    private User seedUser(String username, Role role, String rawPassword) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@example.com");
        u.setFullName(username);
        u.setRole(role);
        u.setPasswordHash(passwordEncoder.encode(rawPassword));
        return userRepository.saveAndFlush(u);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, password))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("accessToken").asText();
    }

    @Test
    void GET_audit_logs_returns_all_when_no_filter() throws Exception {
        seedUser("alice", Role.DEVELOPER, "secret1");
        String token = login("alice", "secret1");

        mockMvc.perform(get("/audit-logs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").exists());
    }

    @Test
    void GET_audit_logs_requires_authentication() throws Exception {
        mockMvc.perform(get("/audit-logs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void GET_audit_logs_filters_by_entityType_and_action() throws Exception {
        seedUser("alice", Role.DEVELOPER, "secret1");
        String token = login("alice", "secret1");

        mockMvc.perform(get("/audit-logs")
                        .param("entityType", "AUTH")
                        .param("action", "LOGIN")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].entityType").value("AUTH"))
                .andExpect(jsonPath("$[0].action").value("LOGIN"));
    }

    @Test
    void POST_users_writes_a_CREATE_audit_row_with_SYSTEM_actor() throws Exception {
        String json = """
                { "username": "bob", "email": "bob@example.com", "fullName": "Bob",
                  "role": "DEVELOPER", "password": "secret1" }
                """;
        mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());

        List<AuditLog> rows = auditRepo.findAll();
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getAction()).isEqualTo(AuditAction.CREATE);
            assertThat(r.getEntityType()).isEqualTo(EntityType.USER);
            // POST /users is public — no SecurityContext → SYSTEM
            assertThat(r.getActor()).isEqualTo(AuditActor.SYSTEM);
            assertThat(r.getPerformedBy()).isNull();
        });
    }

    @Test
    void POST_users_update_writes_an_UPDATE_audit_row_with_USER_actor() throws Exception {
        User alice = seedUser("alice", Role.DEVELOPER, "secret1");
        String token = login("alice", "secret1");
        auditRepo.deleteAll(); // clear LOGIN row so we isolate UPDATE

        UserUpdateDto dto = new UserUpdateDto("Alice Updated", Role.ADMIN);
        mockMvc.perform(post("/users/update/" + alice.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        List<AuditLog> rows = auditRepo.findAll();
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getAction()).isEqualTo(AuditAction.UPDATE);
            assertThat(r.getEntityType()).isEqualTo(EntityType.USER);
            assertThat(r.getEntityId()).isEqualTo(alice.getId());
            assertThat(r.getActor()).isEqualTo(AuditActor.USER);
            assertThat(r.getPerformedBy()).isEqualTo(alice.getId());
        });
    }

    @Test
    void DELETE_users_writes_a_DELETE_audit_row_with_USER_actor() throws Exception {
        User alice = seedUser("alice", Role.DEVELOPER, "secret1");
        User bob = seedUser("bob", Role.DEVELOPER, "secret2");
        String token = login("alice", "secret1");
        auditRepo.deleteAll();

        mockMvc.perform(delete("/users/" + bob.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        List<AuditLog> rows = auditRepo.findAll();
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getAction()).isEqualTo(AuditAction.DELETE);
            assertThat(r.getEntityType()).isEqualTo(EntityType.USER);
            assertThat(r.getEntityId()).isEqualTo(bob.getId());
            assertThat(r.getActor()).isEqualTo(AuditActor.USER);
            assertThat(r.getPerformedBy()).isEqualTo(alice.getId());
        });
    }

    @Test
    void LOGIN_and_LOGOUT_write_audit_rows() throws Exception {
        User alice = seedUser("alice", Role.DEVELOPER, "secret1");

        String token = login("alice", "secret1");
        mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        List<AuditLog> rows = auditRepo.findAll();
        assertThat(rows).anySatisfy(r -> {
            // LOGIN: SecurityContext not yet populated → SYSTEM
            assertThat(r.getAction()).isEqualTo(AuditAction.LOGIN);
            assertThat(r.getEntityId()).isEqualTo(alice.getId());
            assertThat(r.getActor()).isEqualTo(AuditActor.SYSTEM);
        });
        assertThat(rows).anySatisfy(r -> {
            // LOGOUT: filter populated SecurityContext → USER
            assertThat(r.getAction()).isEqualTo(AuditAction.LOGOUT);
            assertThat(r.getEntityId()).isEqualTo(alice.getId());
            assertThat(r.getActor()).isEqualTo(AuditActor.USER);
            assertThat(r.getPerformedBy()).isEqualTo(alice.getId());
        });
    }
}
