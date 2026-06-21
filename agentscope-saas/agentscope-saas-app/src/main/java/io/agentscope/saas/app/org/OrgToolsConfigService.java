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
package io.agentscope.saas.app.org;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.agentscope.saas.core.persistence.entity.OrgEntity;
import io.agentscope.saas.core.persistence.repo.OrgRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads and writes the org-level base MCP-server config stored under the {@code mcpServers} sub-key
 * of {@link OrgEntity#getSettings()} (a JSONB object, default {@code {}}).
 *
 * <p>The org base is the first layer of the two-layer {@code ToolsConfig} merge: a user's effective
 * MCP set is the org base overlaid with that user's workspace {@code tools.json} (see {@link
 * io.agentscope.harness.agent.tools.ToolsConfigMerger}). Only the {@code mcpServers} sub-key is
 * managed here; other {@code settings} keys are preserved on write.
 *
 * <p>Failures are logged and degrade to an empty config rather than throwing — a malformed settings
 * blob never breaks the agent's MCP resolution.
 */
@Component
public class OrgToolsConfigService {

    private static final Logger log = LoggerFactory.getLogger(OrgToolsConfigService.class);

    static final String MCP_SERVERS_KEY = "mcpServers";

    private final OrgRepository orgRepository;
    private final ObjectMapper objectMapper;

    public OrgToolsConfigService(OrgRepository orgRepository, ObjectMapper objectMapper) {
        this.orgRepository = orgRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Loads the org-level base {@link ToolsConfig} (MCP servers only; builtin filters are not
     * org-scoped). Returns an empty {@link ToolsConfig} when the org or its {@code mcpServers}
     * sub-key is absent, or when the settings blob is unreadable.
     */
    public ToolsConfig loadOrgBase(UUID orgId) {
        if (orgId == null) {
            return new ToolsConfig();
        }
        OrgEntity org = orgRepository.findById(orgId).orElse(null);
        if (org == null || org.getSettings() == null || org.getSettings().isBlank()) {
            return new ToolsConfig();
        }
        try {
            JsonNode root = objectMapper.readTree(org.getSettings());
            JsonNode servers = root.get(MCP_SERVERS_KEY);
            if (servers == null || servers.isNull() || !servers.isObject()) {
                return new ToolsConfig();
            }
            Map<String, McpServerConfig> map = new LinkedHashMap<>();
            servers.fields()
                    .forEachRemaining(
                            e -> {
                                try {
                                    McpServerConfig cfg =
                                            objectMapper.treeToValue(
                                                    e.getValue(), McpServerConfig.class);
                                    if (e.getKey() != null && cfg != null) {
                                        map.put(e.getKey(), cfg);
                                    }
                                } catch (Exception ex) {
                                    log.warn(
                                            "Skipping org MCP server '{}' (parse error): {}",
                                            e.getKey(),
                                            ex.getMessage());
                                }
                            });
            ToolsConfig cfg = new ToolsConfig();
            cfg.setMcpServers(map);
            return cfg;
        } catch (Exception e) {
            log.warn("Failed to parse org {} settings: {}", orgId, e.getMessage());
            return new ToolsConfig();
        }
    }

    /**
     * Writes the org-level {@code mcpServers} sub-key, preserving any other keys already in {@code
     * settings}. Returns the persisted (merged) settings JSON string.
     */
    public String saveOrgBase(UUID orgId, Map<String, McpServerConfig> mcpServers) {
        OrgEntity org =
                orgRepository
                        .findById(orgId)
                        .orElseThrow(() -> new IllegalArgumentException("Org not found: " + orgId));
        ObjectNode root;
        if (org.getSettings() != null && !org.getSettings().isBlank()) {
            try {
                root = (ObjectNode) objectMapper.readTree(org.getSettings());
            } catch (Exception e) {
                log.warn(
                        "Org {} settings unreadable ({}); overwriting with fresh object.",
                        orgId,
                        e.getMessage());
                root = objectMapper.createObjectNode();
            }
        } else {
            root = objectMapper.createObjectNode();
        }
        // Replace only the mcpServers sub-key; preserve the rest of settings.
        ObjectNode serversNode =
                objectMapper.valueToTree(mcpServers != null ? mcpServers : Map.of());
        root.set(MCP_SERVERS_KEY, serversNode);
        String merged;
        try {
            merged = objectMapper.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // We just built root ourselves, so this should never fail; surface it rather than
            // silently dropping the admin's config update.
            throw new IllegalStateException("Failed to serialize org settings", e);
        }
        org.setSettings(merged);
        orgRepository.save(org);
        return merged;
    }
}
