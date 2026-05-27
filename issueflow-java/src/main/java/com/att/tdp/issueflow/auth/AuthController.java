package com.att.tdp.issueflow.auth;

import com.att.tdp.issueflow.auth.dto.LoginRequest;
import com.att.tdp.issueflow.auth.dto.LoginResponse;
import com.att.tdp.issueflow.common.audit.AuditService;
import com.att.tdp.issueflow.common.domain.AuditAction;
import com.att.tdp.issueflow.common.domain.EntityType;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import com.att.tdp.issueflow.user.dto.UserResponseDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final TokenDenylistService denylistService;
    private final UserRepository userRepository;
    private final AuditService auditService;

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest body) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(body.username(), body.password()));
        } catch (org.springframework.security.core.AuthenticationException ex) {
            throw new BadCredentialsException("Invalid username or password.");
        }

        User user = userRepository.findByUsername(body.username())
                .orElseThrow(() -> new BadCredentialsException("Invalid username or password."));

        JwtService.Issued issued = jwtService.issue(user);
        // No SecurityContext yet — log() records actor=SYSTEM/performedBy=null with entityId=user.id.
        auditService.log(AuditAction.LOGIN, EntityType.AUTH, user.getId());
        return LoginResponse.bearer(issued.token(), issued.expiresInSeconds());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser principal) {
            denylistService.deny(principal.jti(), principal.tokenExpiresAt());
            auditService.log(AuditAction.LOGOUT, EntityType.AUTH, principal.userId());
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/me")
    public UserResponseDto me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedUser principal)) {
            throw new BadCredentialsException("Not authenticated.");
        }
        User user = userRepository.findById(principal.userId())
                .orElseThrow(() -> NotFoundException.of("User", principal.userId()));
        return UserResponseDto.from(user);
    }
}
