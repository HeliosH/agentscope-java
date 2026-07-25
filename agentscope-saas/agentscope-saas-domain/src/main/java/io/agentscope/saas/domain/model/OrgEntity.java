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
package io.agentscope.saas.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** An organization (tenant). The top of the Org → User isolation hierarchy. */
public class OrgEntity {

    private UUID id;

    private String name;

    private String slug;

    private String status;

    /**
     * Org-wide settings bag stored as a JSONB object (V1 default {@code '{}'}). Currently holds
     * org-level MCP-server base config under the {@code mcpServers} sub-key (see {@code
     * OrgToolsConfigController}); extensible for future org policy. Mapped as a raw JSON string so
     * callers parse/serialize the relevant sub-keys with their own ObjectMapper.
     */
    private String settings;

    private OffsetDateTime createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public String getSettings() {
        return settings;
    }

    public void setSettings(String settings) {
        this.settings = settings;
    }
}
