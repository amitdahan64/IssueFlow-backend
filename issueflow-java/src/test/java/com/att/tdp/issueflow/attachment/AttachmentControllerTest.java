package com.att.tdp.issueflow.attachment;

import com.att.tdp.issueflow.audit.AuditLogRepository;
import com.att.tdp.issueflow.auth.TokenDenylistRepository;
import com.att.tdp.issueflow.auth.dto.LoginRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AttachmentControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private AttachmentRepository attachmentRepository;
    @Autowired private AuditLogRepository auditRepository;
    @Autowired private TokenDenylistRepository denylistRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private Ticket ticket;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        denylistRepository.deleteAll();
        auditRepository.deleteAll();
        attachmentRepository.deleteAll();
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

        Project p = new Project();
        p.setName("Alpha");
        p.setOwnerId(u.getId());
        p = projectRepository.saveAndFlush(p);

        Ticket t = new Ticket();
        t.setTitle("T");
        t.setStatus(TicketStatus.TODO);
        t.setPriority(Priority.LOW);
        t.setType(TicketType.BUG);
        t.setProjectId(p.getId());
        ticket = ticketRepository.saveAndFlush(t);

        MvcResult res = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("alice", "secret"))))
                .andExpect(status().isOk())
                .andReturn();
        token = objectMapper.readTree(res.getResponse().getContentAsString())
                .get("accessToken").asText();
        auditRepository.deleteAll(); // discard LOGIN
    }

    @Test
    void POST_attachment_accepts_png_and_returns_contract_shape() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "screenshot.png", "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G'});

        mockMvc.perform(multipart("/tickets/" + ticket.getId() + "/attachments")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.ticketId").value(ticket.getId()))
                .andExpect(jsonPath("$.filename").value("screenshot.png"))
                .andExpect(jsonPath("$.contentType").value("image/png"));

        assertThat(attachmentRepository.findAllByTicketId(ticket.getId())).hasSize(1);
        assertThat(auditRepository.findAll()).anyMatch(r ->
                r.getAction() == AuditAction.CREATE && r.getEntityType() == EntityType.ATTACHMENT);
    }

    @Test
    void POST_attachment_accepts_jpeg_pdf_and_plain() throws Exception {
        for (String[] pair : new String[][]{
                {"image/jpeg", "p.jpg"},
                {"application/pdf", "spec.pdf"},
                {"text/plain", "log.txt"}}) {
            MockMultipartFile file = new MockMultipartFile(
                    "file", pair[1], pair[0], new byte[]{1, 2, 3});
            mockMvc.perform(multipart("/tickets/" + ticket.getId() + "/attachments")
                            .file(file)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.contentType").value(pair[0]));
        }
    }

    @Test
    void POST_attachment_rejects_disallowed_mime_with_400() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "virus.exe", "application/x-msdownload", new byte[]{0, 1});

        mockMvc.perform(multipart("/tickets/" + ticket.getId() + "/attachments")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("not allowed")));
    }

    @Test
    void POST_attachment_rejects_empty_file_with_400() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "blank.png", "image/png", new byte[0]);

        mockMvc.perform(multipart("/tickets/" + ticket.getId() + "/attachments")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_attachment_rejects_oversize_upload_with_413() throws Exception {
        // 11 MB — exceeds the 10 MB cap configured in application.yaml.
        byte[] tooBig = new byte[11 * 1024 * 1024];

        MockMultipartFile file = new MockMultipartFile(
                "file", "huge.png", "image/png", tooBig);

        mockMvc.perform(multipart("/tickets/" + ticket.getId() + "/attachments")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void POST_attachment_404s_when_ticket_missing() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "a.png", "image/png", new byte[]{1});

        mockMvc.perform(multipart("/tickets/99999/attachments")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void POST_attachment_sanitizes_path_traversal_in_filename() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "../../etc/passwd.txt", "text/plain", new byte[]{1});

        mockMvc.perform(multipart("/tickets/" + ticket.getId() + "/attachments")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("passwd.txt"));
    }

    @Test
    void DELETE_attachment_removes_it_and_writes_audit() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "a.png", "image/png", new byte[]{1, 2});
        MvcResult res = mockMvc.perform(multipart("/tickets/" + ticket.getId() + "/attachments")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        long id = objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
        auditRepository.deleteAll(); // isolate the DELETE audit

        mockMvc.perform(delete("/tickets/" + ticket.getId() + "/attachments/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(attachmentRepository.findById(id)).isEmpty();
        assertThat(auditRepository.findAll()).singleElement().satisfies(r -> {
            assertThat(r.getAction()).isEqualTo(AuditAction.DELETE);
            assertThat(r.getEntityType()).isEqualTo(EntityType.ATTACHMENT);
            assertThat(r.getEntityId()).isEqualTo(id);
        });
    }

    @Test
    void DELETE_attachment_404s_when_missing() throws Exception {
        mockMvc.perform(delete("/tickets/" + ticket.getId() + "/attachments/99999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void attachment_endpoints_require_authentication() throws Exception {
        mockMvc.perform(multipart("/tickets/" + ticket.getId() + "/attachments")
                        .file(new MockMultipartFile("file", "a.png", "image/png", new byte[]{1})))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/tickets/" + ticket.getId() + "/attachments/1"))
                .andExpect(status().isUnauthorized());
    }
}
