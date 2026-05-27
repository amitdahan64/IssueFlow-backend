package com.att.tdp.issueflow.auth;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "token_denylist",
        uniqueConstraints = @UniqueConstraint(name = "uk_token_denylist_jti", columnNames = "jti"))
public class TokenDenylist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String jti;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
