package com.att.tdp.issueflow.project;

import com.att.tdp.issueflow.audit.AuditLogRepository;
import com.att.tdp.issueflow.auth.TokenDenylistRepository;
import com.att.tdp.issueflow.auth.dto.LoginRequest;
import com.att.tdp.issueflow.common.domain.AuditAction;
import com.att.tdp.issueflow.common.domain.Role;
import com.att.tdp.issueflow.project.dto.ProjectCreateDto;
import com.att.tdp.issueflow.project.dto.ProjectUpdateDto;
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
class ProjectControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private AuditLogRepository auditRepository;
    @Autowired private TokenDenylistRepository denylistRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private User developer;
    private String developerToken;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        denylistRepository.deleteAll();
        auditRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();

        developer = seedUser("dev1", Role.DEVELOPER, "secret1");
        seedUser("root", Role.ADMIN, "secret2");
        developerToken = login("dev1", "secret1");
        adminToken = login("root", "secret2");
        auditRepository.deleteAll(); // discard LOGIN rows
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
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private Long createProject(String name) throws Exception {
        ProjectCreateDto dto = new ProjectCreateDto(name, "desc", developer.getId());
        MvcResult result = mockMvc.perform(post("/projects")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("id").asLong();
    }

    @Test
    void POST_projects_creates_and_returns_id() throws Exception {
        ProjectCreateDto dto = new ProjectCreateDto("Alpha", "First project", developer.getId());
        mockMvc.perform(post("/projects")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Alpha"))
                .andExpect(jsonPath("$.ownerId").value(developer.getId()));

        assertThat(auditRepository.findAll()).singleElement().satisfies(r ->
                assertThat(r.getAction()).isEqualTo(AuditAction.CREATE));
    }

    @Test
    void POST_projects_returns_404_when_ownerId_unknown() throws Exception {
        ProjectCreateDto dto = new ProjectCreateDto("Alpha", "desc", 9999L);
        mockMvc.perform(post("/projects")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isNotFound());
    }

    @Test
    void POST_projects_returns_400_when_name_blank() throws Exception {
        String json = """
                { "name": "", "description": "x", "ownerId": %d }
                """.formatted(developer.getId());
        mockMvc.perform(post("/projects")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void GET_projects_excludes_soft_deleted() throws Exception {
        Long aliveId = createProject("Alive");
        Long deadId = createProject("Dead");
        mockMvc.perform(delete("/projects/" + deadId)
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/projects")
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("Alive");
        assertThat(body).doesNotContain("Dead");
        assertThat(body).contains(aliveId.toString());
    }

    @Test
    void GET_project_by_id_returns_404_for_soft_deleted() throws Exception {
        Long id = createProject("Dead");
        mockMvc.perform(delete("/projects/" + id)
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/projects/" + id)
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void PATCH_project_updates_name_and_description() throws Exception {
        Long id = createProject("Alpha");
        ProjectUpdateDto dto = new ProjectUpdateDto("Renamed", "New desc");

        mockMvc.perform(patch("/projects/" + id)
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/projects/" + id)
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.description").value("New desc"));
    }

    @Test
    void PATCH_project_skips_null_fields() throws Exception {
        Long id = createProject("Alpha");
        String json = """
                { "description": "Only-desc updated" }
                """;
        mockMvc.perform(patch("/projects/" + id)
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());

        mockMvc.perform(get("/projects/" + id)
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(jsonPath("$.name").value("Alpha"))
                .andExpect(jsonPath("$.description").value("Only-desc updated"));
    }

    @Test
    void PATCH_project_rejects_explicit_blank_name() throws Exception {
        Long id = createProject("Alpha");
        String json = """
                { "name": "  " }
                """;
        mockMvc.perform(patch("/projects/" + id)
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void GET_deleted_returns_403_for_non_admin() throws Exception {
        mockMvc.perform(get("/projects/deleted")
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void GET_deleted_returns_soft_deleted_for_admin() throws Exception {
        Long id = createProject("Dead");
        mockMvc.perform(delete("/projects/" + id)
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/projects/deleted")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].name").value("Dead"));
    }

    @Test
    void POST_restore_returns_403_for_non_admin() throws Exception {
        Long id = createProject("Dead");
        mockMvc.perform(delete("/projects/" + id)
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/projects/" + id + "/restore")
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void POST_restore_brings_project_back_for_admin() throws Exception {
        Long id = createProject("Dead");
        mockMvc.perform(delete("/projects/" + id)
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/projects/" + id + "/restore")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/projects/" + id)
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Dead"));
    }

    @Test
    void POST_restore_400s_when_project_is_not_deleted() throws Exception {
        Long id = createProject("Alpha");

        mockMvc.perform(post("/projects/" + id + "/restore")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void GET_workload_includes_developers_with_zero_counts() throws Exception {
        Long id = createProject("Alpha");
        // dev1 (the project owner here) is the only DEVELOPER and has 0 open tickets.
        mockMvc.perform(get("/projects/" + id + "/workload")
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].username").value("dev1"))
                .andExpect(jsonPath("$[0].openTicketCount").value(0));
    }

    @Test
    void all_project_endpoints_require_authentication() throws Exception {
        mockMvc.perform(get("/projects")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/projects/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isUnauthorized());
    }
}
