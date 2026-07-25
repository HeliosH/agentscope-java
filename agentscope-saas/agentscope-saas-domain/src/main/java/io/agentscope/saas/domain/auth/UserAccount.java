/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.domain.auth;

import java.util.UUID;

/** Authentication identity required by the login and registration use cases. */
public record UserAccount(
        UUID id,
        UUID orgId,
        String email,
        String displayName,
        String passwordHash,
        String role,
        String tier) {}
