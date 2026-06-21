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
package io.agentscope.harness.agent.tools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Merges two {@link ToolsConfig} MCP-server maps with user-override semantics: entries in the user
 * config replace same-name entries in the org base, and org entries the user did not override are
 * inherited. The {@code allow}/{@code deny} builtin filters are taken from the user config only
 * (builtins are a per-user preference, not org policy) — when the user config has none, the org
 * base's filter is used as the fallback.
 *
 * <p>Pure function with no I/O; unit-testable in isolation.
 */
public final class ToolsConfigMerger {

    private ToolsConfigMerger() {}

    /**
     * Returns the effective MCP-server map: org base overlaid with user overrides.
     *
     * @param orgBase org-level {@code mcpServers} (may be {@code null}/empty)
     * @param userOverride per-user {@code mcpServers} (may be {@code null}/empty)
     */
    public static Map<String, McpServerConfig> mergeMcpServers(
            Map<String, McpServerConfig> orgBase, Map<String, McpServerConfig> userOverride) {
        Map<String, McpServerConfig> out = new LinkedHashMap<>();
        if (orgBase != null) {
            // Insertion order: org base first so user overrides land on top.
            for (Map.Entry<String, McpServerConfig> e : orgBase.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    out.put(e.getKey(), e.getValue());
                }
            }
        }
        if (userOverride != null) {
            for (Map.Entry<String, McpServerConfig> e : userOverride.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    out.put(e.getKey(), e.getValue());
                }
            }
        }
        return out;
    }

    /**
     * Returns the effective builtin filter: the user's {@code allow}/{@code deny} when present,
     * otherwise the org base's. Encoded as a two-element array {@code [allow, deny]} (either may be
     * {@code null}).
     */
    public static ToolsConfig merge(ToolsConfig orgBase, ToolsConfig userOverride) {
        ToolsConfig out = new ToolsConfig();
        Map<String, McpServerConfig> servers =
                mergeMcpServers(
                        orgBase != null ? orgBase.getMcpServers() : null,
                        userOverride != null ? userOverride.getMcpServers() : null);
        out.setMcpServers(servers);
        // Builtin filters: user wins; fall back to org base only if the user set nothing at all.
        ToolsConfig filterSource =
                userOverride != null
                                && (userOverride.getAllow() != null
                                        || userOverride.getDeny() != null)
                        ? userOverride
                        : orgBase;
        if (filterSource != null) {
            out.setAllow(filterSource.getAllow());
            out.setDeny(filterSource.getDeny());
        }
        return out;
    }
}
