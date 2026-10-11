/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.domain.orchestration;

import java.util.Optional;
import java.util.UUID;

/** No update operation: retries and children inherit their Run's accepted contract. */
public interface RunExecutionPolicyRepository {
    void insert(RunExecutionPolicy policy);

    Optional<RunExecutionPolicy> find(UUID orgId, UUID runId);

    boolean requiresSnapshot(UUID orgId, UUID runId);
}
