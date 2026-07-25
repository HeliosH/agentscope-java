/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.dal.mybatis.tenant;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Persistence-only projection for durable task queries. */
public record DurableTaskData(
        UUID id,
        UUID runId,
        UUID agentId,
        String externalTaskId,
        String agentType,
        String status,
        String outputJson,
        String errorMessage,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt,
        OffsetDateTime deliveredAt) {}
