/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.domain.memory;

import java.util.UUID;

/** Durable memory ledger event awaiting projection into the semantic memory index. */
public record MemoryProjectionEvent(
        UUID id,
        UUID orgId,
        UUID userId,
        String agentId,
        String sessionId,
        String contentJson,
        String metadataJson) {}
