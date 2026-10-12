/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.domain.sandbox;

import io.agentscope.saas.domain.sandbox.SandboxUsageRepository.Resource;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Administrative expiration/release port; provider IO stays outside the claim transaction. */
public interface SandboxUsageGcRepository {
    List<Resource> candidates(
            String deploymentKey, OffsetDateTime staleBefore, int maxAttempts, int limit);

    Optional<Resource> claim(
            UUID id, UUID token, OffsetDateTime staleBefore, OffsetDateTime until, int maxAttempts);

    boolean finish(UUID id, UUID token, boolean success);
}
