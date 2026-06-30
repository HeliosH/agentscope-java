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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically evicts sandboxes that have exceeded their idle TTL. The {@code expires_at}
 * column is set when a sandbox is registered by the {@link SandboxBroker}, calculated as
 * {@code now + idleTtlSeconds} from the user's tier policy.
 *
 * <p>Eviction marks the sandbox record as {@code "evicted"} in the database. The actual
 * container/backend resource is cleaned up by the framework's sandbox lifecycle (stop + shutdown
 * on the next release), or by a separate infrastructure-level garbage collector.
 */
@Component
public class SandboxEvictionJob {

    private static final Logger log = LoggerFactory.getLogger(SandboxEvictionJob.class);

    private final SandboxBroker broker;
    private final SandboxInventoryMetrics inventoryMetrics;

    public SandboxEvictionJob(SandboxBroker broker) {
        this(broker, null);
    }

    @Autowired
    public SandboxEvictionJob(SandboxBroker broker, SandboxInventoryMetrics inventoryMetrics) {
        this.broker = broker;
        this.inventoryMetrics = inventoryMetrics;
    }

    /** Run every 60 seconds to evict expired sandboxes. */
    @Scheduled(fixedDelay = 60_000)
    public void evictExpired() {
        try {
            refreshInventoryMetrics();
            int evicted = broker.evictExpired();
            if (evicted > 0) {
                log.info("Evicted {} expired sandbox(es)", evicted);
            }
        } catch (Exception e) {
            log.warn("Sandbox eviction job failed: {}", e.getMessage());
        } finally {
            refreshInventoryMetrics();
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
