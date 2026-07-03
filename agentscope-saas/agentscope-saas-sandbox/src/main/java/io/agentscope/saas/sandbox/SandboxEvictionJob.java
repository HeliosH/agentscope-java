/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.saas.sandbox;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically evicts sandboxes that have exceeded their idle TTL. The {@code expires_at}
 * column is set when a sandbox is registered by the {@link SandboxBroker}, calculated as
 * {@code now + idleTtlSeconds} from the user's tier policy.
 *
 * <p>Eviction marks the sandbox record as {@code "evicted"} in the database and then best-effort
 * terminates the provider-owned backend resource using {@link SandboxBackendTerminator}. The DB
 * update happens first so quota pressure is released even if the provider delete call is degraded.
 */
@Component
public class SandboxEvictionJob {

    private static final Logger log = LoggerFactory.getLogger(SandboxEvictionJob.class);

    private final SandboxBroker broker;
    private final SandboxInventoryMetrics inventoryMetrics;
    private final SandboxBackendTerminator terminator;
    private final SandboxMetrics metrics;

    public SandboxEvictionJob(SandboxBroker broker) {
        this(broker, null);
    }

    @Autowired
    public SandboxEvictionJob(
            SandboxBroker broker,
            SandboxInventoryMetrics inventoryMetrics,
            ObjectProvider<SandboxBackendTerminator> terminatorProvider,
            SandboxMetrics metrics) {
        this(
                broker,
                inventoryMetrics,
                terminatorProvider != null
                        ? terminatorProvider.getIfAvailable(SandboxBackendTerminator::unsupported)
                        : SandboxBackendTerminator.unsupported(),
                metrics);
    }

    public SandboxEvictionJob(SandboxBroker broker, SandboxInventoryMetrics inventoryMetrics) {
        this(
                broker,
                inventoryMetrics,
                SandboxBackendTerminator.unsupported(),
                SandboxMetrics.noop());
    }

    SandboxEvictionJob(
            SandboxBroker broker,
            SandboxInventoryMetrics inventoryMetrics,
            SandboxBackendTerminator terminator,
            SandboxMetrics metrics) {
        this.broker = broker;
        this.inventoryMetrics = inventoryMetrics;
        this.terminator = terminator != null ? terminator : SandboxBackendTerminator.unsupported();
        this.metrics = metrics != null ? metrics : SandboxMetrics.noop();
    }

    /** Run every 60 seconds to evict expired sandboxes. */
    @Scheduled(fixedDelay = 60_000)
    public void evictExpired() {
        try {
            refreshInventoryMetrics();
            List<SandboxBroker.EvictedSandbox> evicted = broker.evictExpiredWithDetails();
            if (!evicted.isEmpty()) {
                log.info("Evicted {} expired sandbox(es)", evicted.size());
                evicted.forEach(this::terminateBackend);
            }
        } catch (Exception e) {
            log.warn("Sandbox eviction job failed: {}", e.getMessage());
        } finally {
            refreshInventoryMetrics();
        }
    }

    private void terminateBackend(SandboxBroker.EvictedSandbox sandbox) {
        try {
            SandboxBackendTerminator.TerminationResult result =
                    terminator.terminate(sandbox.sandboxType(), sandbox.externalId());
            broker.recordBackendRelease(sandbox.id(), result);
            if (result.attempted() && result.succeeded()) {
                metrics.backendReleaseSucceeded(sandbox.sandboxType());
                log.debug(
                        "Terminated expired sandbox backend id={} type={} externalId={}",
                        sandbox.id(),
                        sandbox.sandboxType(),
                        sandbox.externalId());
            } else if (result.attempted()) {
                metrics.backendReleaseFailed(sandbox.sandboxType());
                log.warn(
                        "Expired sandbox backend termination failed id={} type={} externalId={} "
                                + "status={} message={}",
                        sandbox.id(),
                        sandbox.sandboxType(),
                        sandbox.externalId(),
                        result.status(),
                        result.message());
            } else {
                log.debug(
                        "Skipped expired sandbox backend termination id={} type={} externalId={} "
                                + "status={} message={}",
                        sandbox.id(),
                        sandbox.sandboxType(),
                        sandbox.externalId(),
                        result.status(),
                        result.message());
            }
        } catch (Exception e) {
            broker.recordBackendRelease(
                    sandbox.id(),
                    SandboxBackendTerminator.TerminationResult.failed(
                            e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
            metrics.backendReleaseFailed(sandbox.sandboxType());
            log.warn(
                    "Expired sandbox backend termination threw id={} type={} externalId={}: {}",
                    sandbox.id(),
                    sandbox.sandboxType(),
                    sandbox.externalId(),
                    e.getMessage());
        }
    }

    private void refreshInventoryMetrics() {
        if (inventoryMetrics == null) {
            return;
        }
        try {
            inventoryMetrics.refresh();
        } catch (Exception e) {
            log.warn("Sandbox inventory metrics refresh failed: {}", e.getMessage());
        }
    }
}
