package com.att.tdp.issueflow.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {

    /** Bypasses @SQLRestriction — returns soft-deleted projects. */
    @Query(value = "SELECT * FROM projects WHERE deleted_at IS NOT NULL", nativeQuery = true)
    List<Project> findAllSoftDeleted();

    /** Bypasses @SQLRestriction — returns the row even if soft-deleted. */
    @Query(value = "SELECT * FROM projects WHERE id = ?1", nativeQuery = true)
    Optional<Project> findByIdIncludingDeleted(Long id);
}
