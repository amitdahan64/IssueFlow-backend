package com.att.tdp.issueflow.project;

import com.att.tdp.issueflow.common.config.JpaAuditingConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase
@Import(JpaAuditingConfig.class)
class ProjectRepositoryTest {

    @Autowired private ProjectRepository projectRepository;
    @Autowired private TestEntityManager em;

    private Project newProject(String name, Long ownerId) {
        Project p = new Project();
        p.setName(name);
        p.setDescription("desc");
        p.setOwnerId(ownerId);
        return p;
    }

    @BeforeEach
    void clean() {
        em.getEntityManager().createNativeQuery("DELETE FROM projects").executeUpdate();
    }

    @Test
    void saves_and_finds_a_project() {
        Project saved = projectRepository.save(newProject("Alpha", 1L));
        assertThat(saved.getId()).isNotNull();

        List<Project> all = projectRepository.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getName()).isEqualTo("Alpha");
    }

    @Test
    void soft_deleted_projects_are_hidden_from_standard_queries() {
        Project p = projectRepository.saveAndFlush(newProject("Alpha", 1L));
        p.setDeletedAt(Instant.now());
        projectRepository.saveAndFlush(p);
        em.clear(); // forget the persistence-context entity so SQLRestriction is exercised

        assertThat(projectRepository.findAll()).isEmpty();
        assertThat(projectRepository.findById(p.getId())).isEmpty();
    }

    @Test
    void findAllSoftDeleted_returns_only_soft_deleted_rows() {
        projectRepository.saveAndFlush(newProject("Alive", 1L));
        Project b = projectRepository.saveAndFlush(newProject("Dead", 1L));
        b.setDeletedAt(Instant.now());
        projectRepository.saveAndFlush(b);
        em.clear();

        List<Project> deleted = projectRepository.findAllSoftDeleted();

        assertThat(deleted).hasSize(1);
        assertThat(deleted.get(0).getId()).isEqualTo(b.getId());
        assertThat(deleted.get(0).getName()).isEqualTo("Dead");
    }

    @Test
    void findByIdIncludingDeleted_returns_soft_deleted_row() {
        Project p = projectRepository.saveAndFlush(newProject("Dead", 1L));
        p.setDeletedAt(Instant.now());
        projectRepository.saveAndFlush(p);
        em.clear();

        Optional<Project> found = projectRepository.findByIdIncludingDeleted(p.getId());

        assertThat(found).isPresent();
        assertThat(found.get().isDeleted()).isTrue();
    }
}
