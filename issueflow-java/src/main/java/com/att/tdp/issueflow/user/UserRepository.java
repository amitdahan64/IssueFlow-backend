package com.att.tdp.issueflow.user;

import com.att.tdp.issueflow.common.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByUsernameIgnoreCase(String username);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    /** Used by auto-assignment (oldest registration first wins ties). */
    List<User> findAllByRoleOrderByCreatedAtAsc(Role role);
}
