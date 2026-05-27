package com.att.tdp.issueflow.auth;

import com.att.tdp.issueflow.common.domain.Role;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Idempotently creates a bootstrap ADMIN user so the demo login flow works
 * out of the box. Skipped during tests via the !test profile.
 */
@Component
@Profile("!test")
@RequiredArgsConstructor
public class AdminSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${issueflow.bootstrap.admin-username:admin}")
    private String adminUsername;

    @Value("${issueflow.bootstrap.admin-email:admin@issueflow.local}")
    private String adminEmail;

    @Value("${issueflow.bootstrap.admin-password:admin12345}")
    private String adminPassword;

    @Override
    public void run(String... args) {
        if (userRepository.existsByUsername(adminUsername)) {
            return;
        }
        User admin = new User();
        admin.setUsername(adminUsername);
        admin.setEmail(adminEmail);
        admin.setFullName("System Administrator");
        admin.setRole(Role.ADMIN);
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        userRepository.save(admin);
        log.info("Seeded bootstrap ADMIN user '{}' (configurable via issueflow.bootstrap.* properties).", adminUsername);
    }
}
