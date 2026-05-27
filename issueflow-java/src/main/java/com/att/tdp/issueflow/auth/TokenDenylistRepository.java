package com.att.tdp.issueflow.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface TokenDenylistRepository extends JpaRepository<TokenDenylist, Long> {

    boolean existsByJti(String jti);

    @Modifying
    @Query("delete from TokenDenylist t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
