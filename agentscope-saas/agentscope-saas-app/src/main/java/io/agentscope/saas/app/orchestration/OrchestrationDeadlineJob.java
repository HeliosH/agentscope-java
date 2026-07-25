/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.saas.app.orchestration;

import io.agentscope.saas.app.config.SaasProperties;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Enforces persisted deadlines even when work is queued or an execution has stalled. */
@Component
public class OrchestrationDeadlineJob {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationDeadlineJob.class);

    private final OrchestrationGovernanceService governance;
    private final SaasProperties properties;

    public OrchestrationDeadlineJob(
            OrchestrationGovernanceService governance, SaasProperties properties) {
        this.governance = governance;
        this.properties = properties;
    }

    @Scheduled(
            initialDelayString = "${saas.orchestration.deadline-sweep-fixed-delay-seconds:5}",
            fixedDelayString = "${saas.orchestration.deadline-sweep-fixed-delay-seconds:5}",
            timeUnit = TimeUnit.SECONDS)
    public void expireScheduled() {
        if (!properties.getOrchestration().isEnabled()
                || !properties.getOrchestration().isBudgetEnforcementEnabled()) {
            return;
        }
        try {
            int expired =
                    governance.expireDue(
                            Math.max(1, properties.getOrchestration().getSchedulerBatchSize()));
            if (expired > 0) {
                log.warn("Terminated {} orchestration deadline scope(s)", expired);
            }
        } catch (RuntimeException e) {
            log.warn("Orchestration deadline sweep failed: {}", e.getMessage());
        }
    }
}
