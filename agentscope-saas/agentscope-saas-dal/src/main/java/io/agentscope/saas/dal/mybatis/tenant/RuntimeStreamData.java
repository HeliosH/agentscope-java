/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.dal.mybatis.tenant;

import java.util.UUID;

public record RuntimeStreamData(
        UUID id,
        UUID orgId,
        UUID userId,
        UUID agentId,
        UUID sessionId,
        String agentLabel,
        String sessionKey,
        long lastSeq,
        String lastMessageId) {}
