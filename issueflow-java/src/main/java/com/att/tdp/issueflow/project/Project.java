package com.att.tdp.issueflow.project;

import com.att.tdp.issueflow.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "projects", indexes = @Index(name = "ix_projects_owner", columnList = "owner_id"))
@SQLRestriction("deleted_at IS NULL")
public class Project extends BaseEntity {

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
