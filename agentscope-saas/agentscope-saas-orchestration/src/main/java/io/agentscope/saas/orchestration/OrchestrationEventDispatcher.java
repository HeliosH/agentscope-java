/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.orchestration;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Delivery boundary for durable Run events. Implementations must tolerate duplicate event ids:
 * Outbox delivery is at least once when a worker fails after dispatch but before acknowledgement.
 */
@FunctionalInterface
public interface OrchestrationEventDispatcher {

    void dispatch(OutboxEvent event);

    record OutboxEvent(
            UUID id,
            UUID orgId,
            UUID aggregateId,
            String aggregateType,
            String eventType,
            String payloadJson,
            OffsetDateTime createdAt) {}
}
