/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.saas.core.tenant;

import java.util.Map;

/**
 * Resolves a {@link TenantContext} from authentication material (JWT claims). Implementations look
 * up the user's quota tier policy to populate sandbox/token limits.
 */
public interface TenantResolver {

    /**
     * Resolve a tenant context from decoded JWT claims.
     *
     * @param claims decoded JWT claims (must contain org_id and user_id)
     * @return the resolved tenant context, never {@code null}
     */
    TenantContext resolve(Map<String, Object> claims);
}
