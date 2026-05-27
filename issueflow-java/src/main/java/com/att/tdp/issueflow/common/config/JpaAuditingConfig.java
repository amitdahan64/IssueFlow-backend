package com.att.tdp.issueflow.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Isolated so @WebMvcTest slices (which filter out plain @Configuration classes)
 * don't try to construct jpaAuditingHandler against an empty metamodel.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
