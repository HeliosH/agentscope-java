/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.sandbox;

import java.util.UUID;

/** Runtime-scoped pointer to the orchestration sandbox lease for the current agent call. */
public record SandboxLeaseContext(UUID leaseId, UUID orgId) {}
