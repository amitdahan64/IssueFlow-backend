package com.att.tdp.issueflow.audit;

import com.att.tdp.issueflow.common.domain.AuditAction;
import com.att.tdp.issueflow.common.domain.AuditActor;
import com.att.tdp.issueflow.common.domain.EntityType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public final class AuditLogSpecifications {

    private AuditLogSpecifications() {}

    public static Specification<AuditLog> withFilters(EntityType entityType,
                                                      Long entityId,
                                                      AuditAction action,
                                                      AuditActor actor) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (entityType != null) predicates.add(cb.equal(root.get("entityType"), entityType));
            if (entityId != null)   predicates.add(cb.equal(root.get("entityId"), entityId));
            if (action != null)     predicates.add(cb.equal(root.get("action"), action));
            if (actor != null)      predicates.add(cb.equal(root.get("actor"), actor));
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
