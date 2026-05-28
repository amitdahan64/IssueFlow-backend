package com.att.tdp.issueflow.escalation;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Thin {@code @Scheduled} wrapper. Disabled by setting
 * {@code issueflow.escalation.enabled=false} (the integration-test profile
 * does this so tests can invoke {@link EscalationService#runOnce()} directly
 * without competing with background ticks).
 */
@Component
@ConditionalOnProperty(
        prefix = "issueflow.escalation",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@RequiredArgsConstructor
public class EscalationScheduler {

    private final EscalationService service;

    @Scheduled(fixedDelayString = "${issueflow.escalation.fixed-delay-ms}")
    public void runScheduled() {
        service.runOnce();
    }
}
