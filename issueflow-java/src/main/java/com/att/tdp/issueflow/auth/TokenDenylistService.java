package com.att.tdp.issueflow.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class TokenDenylistService {

    private final TokenDenylistRepository repository;

    @Transactional(readOnly = true)
    public boolean isDenied(String jti) {
        return jti != null && repository.existsByJti(jti);
    }

    @Transactional
    public void deny(String jti, Instant expiresAt) {
        if (repository.existsByJti(jti)) {
            return;
        }
        TokenDenylist entry = new TokenDenylist();
        entry.setJti(jti);
        entry.setExpiresAt(expiresAt);
        repository.save(entry);
    }

    @Transactional
    public int purgeExpired(Instant cutoff) {
        return repository.deleteExpiredBefore(cutoff);
    }
}
