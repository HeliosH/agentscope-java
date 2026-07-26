/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.mybatis.admin;

import java.util.UUID;

/** Administrative projection of a provider resource owned by an orchestration lease. */
public record OrchestrationLeaseResourceData(
        UUID id, UUID orgId, UUID userId, String providerId, String providerSandboxId) {}
