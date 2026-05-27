package com.att.tdp.issueflow.user;

import com.att.tdp.issueflow.common.domain.Role;
import com.att.tdp.issueflow.common.error.ConflictException;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.user.dto.UserCreateDto;
import com.att.tdp.issueflow.user.dto.UserUpdateDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserService userService;

    private User sampleUser(Long id) {
        User u = new User();
        u.setUsername("jdoe");
        u.setEmail("jdoe@example.com");
        u.setFullName("John Doe");
        u.setRole(Role.DEVELOPER);
        u.setPasswordHash("hash");
        u.setId(id);
        return u;
    }

    @Test
    void GET_users_returns_list() throws Exception {
        given(userService.findAll()).willReturn(List.of(sampleUser(1L)));

        mockMvc.perform(get("/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].username").value("jdoe"))
                .andExpect(jsonPath("$[0].role").value("DEVELOPER"))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    @Test
    void GET_user_by_id_returns_user() throws Exception {
        given(userService.findById(1L)).willReturn(sampleUser(1L));

        mockMvc.perform(get("/users/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.email").value("jdoe@example.com"));
    }

    @Test
    void GET_user_by_id_returns_404_when_missing() throws Exception {
        given(userService.findById(99L)).willThrow(NotFoundException.of("User", 99L));

        mockMvc.perform(get("/users/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("User 99 not found"));
    }

    @Test
    void POST_users_creates_a_user() throws Exception {
        UserCreateDto dto = new UserCreateDto("jdoe", "jdoe@example.com", "John Doe", Role.DEVELOPER, "secret1");
        given(userService.create(any())).willReturn(sampleUser(5L));

        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void POST_users_rejects_blank_username() throws Exception {
        String json = """
                { "username": "", "email": "x@x.com", "fullName": "X", "role": "DEVELOPER", "password": "secret1" }
                """;

        mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(org.hamcrest.Matchers.hasItem("username")));
    }

    @Test
    void POST_users_rejects_invalid_email() throws Exception {
        String json = """
                { "username": "jdoe", "email": "not-an-email", "fullName": "X", "role": "DEVELOPER", "password": "secret1" }
                """;

        mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(org.hamcrest.Matchers.hasItem("email")));
    }

    @Test
    void POST_users_rejects_unknown_role() throws Exception {
        String json = """
                { "username": "jdoe", "email": "j@x.com", "fullName": "X", "role": "SUPERADMIN", "password": "secret1" }
                """;

        mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_users_returns_409_on_duplicate() throws Exception {
        UserCreateDto dto = new UserCreateDto("jdoe", "jdoe@example.com", "John Doe", Role.DEVELOPER, "secret1");
        given(userService.create(any())).willThrow(new ConflictException("Username 'jdoe' is already taken."));

        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void POST_users_update_returns_200() throws Exception {
        UserUpdateDto dto = new UserUpdateDto("Jane Doe", Role.ADMIN);

        mockMvc.perform(post("/users/update/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk());

        verify(userService).update(eq(3L), any(UserUpdateDto.class));
    }

    @Test
    void POST_users_update_returns_404_when_missing() throws Exception {
        UserUpdateDto dto = new UserUpdateDto("Jane Doe", Role.ADMIN);
        willThrow(NotFoundException.of("User", 99L)).given(userService).update(eq(99L), any());

        mockMvc.perform(post("/users/update/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isNotFound());
    }

    @Test
    void DELETE_user_returns_200() throws Exception {
        mockMvc.perform(delete("/users/7"))
                .andExpect(status().isOk());

        verify(userService).delete(7L);
    }

    @Test
    void DELETE_user_returns_404_when_missing() throws Exception {
        willThrow(NotFoundException.of("User", 99L)).given(userService).delete(99L);

        mockMvc.perform(delete("/users/99"))
                .andExpect(status().isNotFound());
    }
}
