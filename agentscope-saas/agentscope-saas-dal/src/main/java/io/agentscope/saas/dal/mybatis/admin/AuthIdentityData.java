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

import java.util.UUID;

/** MyBatis data objects used by pre-tenant authentication queries. */
public final class AuthIdentityData {

    private AuthIdentityData() {}

    public record User(
            UUID id,
            UUID orgId,
            String email,
            String displayName,
            String passwordHash,
            String role,
            String tier) {}

    public record Org(UUID id, String name, String slug, String status) {}
}
