package com.att.tdp.issueflow.dependency;

import com.att.tdp.issueflow.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "ticket_dependencies",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_dep_ticket_blocker",
                columnNames = {"ticket_id", "blocker_ticket_id"}
        ),
        indexes = {
                @Index(name = "ix_dep_ticket", columnList = "ticket_id"),
                @Index(name = "ix_dep_blocker", columnList = "blocker_ticket_id")
        })
public class TicketDependency extends BaseEntity {

    /** The blocked ticket. */
    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    /** The ticket that blocks {@link #ticketId}. */
    @Column(name = "blocker_ticket_id", nullable = false)
    private Long blockerTicketId;
}
