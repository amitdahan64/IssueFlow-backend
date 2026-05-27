package com.att.tdp.issueflow.user;

import com.att.tdp.issueflow.common.config.JpaAuditingConfig;
import com.att.tdp.issueflow.common.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase
@Import(JpaAuditingConfig.class)
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    private User newUser(String username, String email) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(email);
        u.setFullName("Test User");
        u.setRole(Role.DEVELOPER);
        u.setPasswordHash("hash");
        return u;
    }

    @Test
    void saves_and_finds_user_by_username() {
        userRepository.save(newUser("jdoe", "jdoe@example.com"));

        Optional<User> found = userRepository.findByUsername("jdoe");

        assertThat(found).isPresent();
        assertThat(found.get().getEmail()).isEqualTo("jdoe@example.com");
        assertThat(found.get().getId()).isNotNull();
    }

    @Test
    void findByUsernameIgnoreCase_matches_case_insensitively() {
        userRepository.save(newUser("jdoe", "jdoe@example.com"));

        assertThat(userRepository.findByUsernameIgnoreCase("JDOE")).isPresent();
        assertThat(userRepository.findByUsernameIgnoreCase("JDoe")).isPresent();
    }

    @Test
    void rejects_duplicate_username() {
        userRepository.saveAndFlush(newUser("jdoe", "a@example.com"));

        assertThatThrownBy(() -> userRepository.saveAndFlush(newUser("jdoe", "b@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejects_duplicate_email() {
        userRepository.saveAndFlush(newUser("a", "shared@example.com"));

        assertThatThrownBy(() -> userRepository.saveAndFlush(newUser("b", "shared@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void existsBy_helpers_work() {
        userRepository.save(newUser("jdoe", "jdoe@example.com"));

        assertThat(userRepository.existsByUsername("jdoe")).isTrue();
        assertThat(userRepository.existsByUsername("nobody")).isFalse();
        assertThat(userRepository.existsByEmail("jdoe@example.com")).isTrue();
        assertThat(userRepository.existsByEmail("nobody@example.com")).isFalse();
    }
}
