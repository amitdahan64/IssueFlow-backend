package com.att.tdp.issueflow.user;

import com.att.tdp.issueflow.common.audit.AuditService;
import com.att.tdp.issueflow.common.domain.AuditAction;
import com.att.tdp.issueflow.common.domain.EntityType;
import com.att.tdp.issueflow.common.error.ConflictException;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.user.dto.UserCreateDto;
import com.att.tdp.issueflow.user.dto.UserUpdateDto;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<User> findAll() {
        return userRepository.findAll();
    }

    @Transactional(readOnly = true)
    public User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("User", id));
    }

    @Transactional
    public User create(UserCreateDto dto) {
        if (userRepository.existsByUsername(dto.username())) {
            throw new ConflictException("Username '" + dto.username() + "' is already taken.");
        }
        if (userRepository.existsByEmail(dto.email())) {
            throw new ConflictException("Email '" + dto.email() + "' is already registered.");
        }
        User user = new User();
        user.setUsername(dto.username());
        user.setEmail(dto.email());
        user.setFullName(dto.fullName());
        user.setRole(dto.role());
        user.setPasswordHash(passwordEncoder.encode(dto.password()));

        User saved = userRepository.save(user);
        auditService.log(AuditAction.CREATE, EntityType.USER, saved.getId());
        return saved;
    }

    @Transactional
    public void update(Long id, UserUpdateDto dto) {
        User user = findById(id);
        user.setFullName(dto.fullName());
        user.setRole(dto.role());
        userRepository.save(user);
        auditService.log(AuditAction.UPDATE, EntityType.USER, user.getId());
    }

    @Transactional
    public void delete(Long id) {
        User user = findById(id);
        userRepository.delete(user);
        auditService.log(AuditAction.DELETE, EntityType.USER, id);
    }
}
