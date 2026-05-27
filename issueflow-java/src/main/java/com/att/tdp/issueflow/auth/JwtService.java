package com.att.tdp.issueflow.auth;

import com.att.tdp.issueflow.common.domain.Role;
import com.att.tdp.issueflow.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_ROLE = "role";

    private final JwtProperties properties;
    private final SecretKey signingKey;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        byte[] keyBytes = properties.getSecret().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "security.jwt.secret must be at least 32 bytes for HS256 (got " + keyBytes.length + ").");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public Issued issue(User user) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(properties.getTtlSeconds());
        String jti = UUID.randomUUID().toString();

        String token = Jwts.builder()
                .subject(user.getId().toString())
                .id(jti)
                .claim(CLAIM_USERNAME, user.getUsername())
                .claim(CLAIM_ROLE, user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();

        return new Issued(token, exp, properties.getTtlSeconds());
    }

    public ParsedJwt parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return new ParsedJwt(
                Long.parseLong(claims.getSubject()),
                claims.get(CLAIM_USERNAME, String.class),
                Role.valueOf(claims.get(CLAIM_ROLE, String.class)),
                claims.getId(),
                claims.getExpiration().toInstant()
        );
    }

    public record Issued(String token, Instant expiresAt, long expiresInSeconds) {}

    public record ParsedJwt(Long userId, String username, Role role, String jti, Instant expiresAt) {}
}
