/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.dal.mybatis.admin;

import java.time.OffsetDateTime;
import java.util.UUID;

/** MyBatis projection of a locked Run and Task budget scope. */
public record OrchestrationBudgetData(
        UUID runId,
        UUID orgId,
        String runStatus,
        Long runTokenBudget,
        long runConsumedTokens,
        Long runCostBudget,
        long runConsumedCost,
        Integer runCallBudget,
        int runConsumedCalls,
        OffsetDateTime runDeadline,
        UUID taskId,
        Long taskTokenBudget,
        long taskConsumedTokens,
        Long taskCostBudget,
        long taskConsumedCost,
        Integer taskCallBudget,
        int taskConsumedCalls,
        OffsetDateTime taskDeadline) {}
