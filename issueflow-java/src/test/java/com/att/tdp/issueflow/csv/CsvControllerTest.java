package com.att.tdp.issueflow.csv;

import com.att.tdp.issueflow.auth.TokenDenylistRepository;
import com.att.tdp.issueflow.auth.dto.LoginRequest;
import com.att.tdp.issueflow.audit.AuditLogRepository;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CsvControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private AuditLogRepository auditRepository;
    @Autowired private TokenDenylistRepository denylistRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private Project source;
    private Project destination;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        denylistRepository.deleteAll();
        auditRepository.deleteAll();
        ticketRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();

        User u = new User();
        u.setUsername("alice");
        u.setEmail("alice@example.com");
        u.setFullName("Alice");
        u.setRole(Role.DEVELOPER);
        u.setPasswordHash(passwordEncoder.encode("secret"));
        u = userRepository.saveAndFlush(u);

        source = saveProject("Source", u.getId());
        destination = saveProject("Destination", u.getId());

        MvcResult res = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("alice", "secret"))))
                .andExpect(status().isOk()).andReturn();
        token = objectMapper.readTree(res.getResponse().getContentAsString())
                .get("accessToken").asText();
        auditRepository.deleteAll();
    }

    private Project saveProject(String name, Long ownerId) {
        Project p = new Project();
        p.setName(name);
        p.setOwnerId(ownerId);
        return projectRepository.saveAndFlush(p);
    }

    private Ticket saveTicket(Long projectId, String title, String description,
                              TicketStatus status, Priority priority, TicketType type) {
        Ticket t = new Ticket();
        t.setTitle(title);
        t.setDescription(description);
        t.setStatus(status);
        t.setPriority(priority);
        t.setType(type);
        t.setProjectId(projectId);
        return ticketRepository.saveAndFlush(t);
    }

    @Test
    void GET_export_returns_csv_with_header_and_rows() throws Exception {
        saveTicket(source.getId(), "Fix login", "auth path broken",
                TicketStatus.TODO, Priority.HIGH, TicketType.BUG);
        saveTicket(source.getId(), "Add dashboard", "report view",
                TicketStatus.IN_PROGRESS, Priority.MEDIUM, TicketType.FEATURE);

        MvcResult res = mockMvc.perform(get("/tickets/export")
                        .param("projectId", source.getId().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.startsWith("text/csv")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andReturn();

        String body = res.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String[] lines = body.split("\r?\n");
        assertThat(lines).hasSize(3); // header + 2 rows
        assertThat(lines[0]).contains("id", "title", "description", "status", "priority", "type", "assigneeId");
        assertThat(body).contains("Fix login").contains("Add dashboard");
    }

    @Test
    void GET_export_includes_only_header_when_no_tickets() throws Exception {
        MvcResult res = mockMvc.perform(get("/tickets/export")
                        .param("projectId", source.getId().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String body = res.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String[] lines = body.split("\r?\n");
        assertThat(lines).hasSize(1);
        assertThat(lines[0]).contains("id");
    }

    @Test
    void POST_import_round_trips_export_into_a_second_project() throws Exception {
        saveTicket(source.getId(), "A", "first",
                TicketStatus.TODO, Priority.LOW, TicketType.BUG);
        saveTicket(source.getId(), "B", "second",
                TicketStatus.IN_PROGRESS, Priority.HIGH, TicketType.FEATURE);
        saveTicket(source.getId(), "C", "third",
                TicketStatus.IN_REVIEW, Priority.CRITICAL, TicketType.TECHNICAL);

        // Export
        byte[] exported = mockMvc.perform(get("/tickets/export")
                        .param("projectId", source.getId().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        // Import into the destination project
        MockMultipartFile file = new MockMultipartFile(
                "file", "export.csv", "text/csv", exported);

        mockMvc.perform(multipart("/tickets/import")
                        .file(file)
                        .param("projectId", destination.getId().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(3))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.errors.length()").value(0));

        List<Ticket> destTickets = ticketRepository.findAllByProjectId(destination.getId());
        assertThat(destTickets).hasSize(3);
        assertThat(destTickets).extracting(Ticket::getTitle)
                .containsExactlyInAnyOrder("A", "B", "C");
    }

    @Test
    void CSV_round_trip_preserves_commas_quotes_and_newlines_in_description() throws Exception {
        String trickyDesc = "Line one, with comma.\nLine two with \"quotes\" inside.";
        saveTicket(source.getId(), "Tricky", trickyDesc,
                TicketStatus.TODO, Priority.LOW, TicketType.BUG);

        byte[] exported = mockMvc.perform(get("/tickets/export")
                        .param("projectId", source.getId().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        MockMultipartFile file = new MockMultipartFile(
                "file", "export.csv", "text/csv", exported);
        mockMvc.perform(multipart("/tickets/import")
                        .file(file)
                        .param("projectId", destination.getId().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.failed").value(0));

        Ticket imported = ticketRepository.findAllByProjectId(destination.getId()).get(0);
        assertThat(imported.getTitle()).isEqualTo("Tricky");
        assertThat(imported.getDescription()).isEqualTo(trickyDesc);
    }

    @Test
    void POST_import_collects_per_row_errors_without_aborting_the_batch() throws Exception {
        String csv = """
                id,title,description,status,priority,type,assigneeId
                ,"good","desc","TODO","HIGH","BUG",
                ,"","missing-title","TODO","HIGH","BUG",
                ,"bad-status","x","FROZEN","HIGH","BUG",
                ,"also-good","y","IN_PROGRESS","LOW","FEATURE",
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file", "in.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/tickets/import")
                        .file(file)
                        .param("projectId", destination.getId().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.failed").value(2))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[*].row")
                        .value(org.hamcrest.Matchers.hasItems(2, 3)));

        List<Ticket> imported = ticketRepository.findAllByProjectId(destination.getId());
        assertThat(imported).extracting(Ticket::getTitle)
                .containsExactlyInAnyOrder("good", "also-good");
    }

    @Test
    void POST_import_returns_empty_summary_for_empty_file() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.csv", "text/csv", new byte[0]);
        mockMvc.perform(multipart("/tickets/import")
                        .file(file)
                        .param("projectId", destination.getId().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.failed").value(0));
    }

    @Test
    void csv_endpoints_require_authentication() throws Exception {
        mockMvc.perform(get("/tickets/export")
                        .param("projectId", source.getId().toString()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(multipart("/tickets/import")
                        .file(new MockMultipartFile("file", "x.csv", "text/csv", "id\n".getBytes()))
                        .param("projectId", destination.getId().toString()))
                .andExpect(status().isUnauthorized());
    }
}
