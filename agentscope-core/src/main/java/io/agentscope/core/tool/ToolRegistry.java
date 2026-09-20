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
package io.agentscope.core.tool;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Internal registry for managing tool registration and lookup.
 *
 * <p>This class maintains mappings between tool names and their implementations, along with
 * metadata about registered tool functions. It is used internally by {@link Toolkit} to organize
 * and retrieve tools.
 *
 * <p><b>Thread Safety:</b> This class is thread-safe, using {@link ConcurrentHashMap} for internal
 * storage to support concurrent tool registration and lookup operations.
 *
 * <p><b>Key Responsibilities:</b>
 * <ul>
 *   <li>Store and retrieve {@link AgentTool} implementations by name</li>
 *   <li>Maintain {@link RegisteredToolFunction} metadata for schema generation</li>
 *   <li>Support dynamic tool removal for group-based activation</li>
 * </ul>
 */
class ToolRegistry {

    private String version = java.util.UUID.randomUUID().toString();

    synchronized String version() {
        return version;
    }

    private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();
    private final Map<String, RegisteredToolFunction> registeredTools = new ConcurrentHashMap<>();

    /**
     * Register a tool with its metadata.
     *
     * @param toolName Tool name
     * @param tool AgentTool implementation
     * @param registered RegisteredToolFunction wrapper with metadata
     */
    synchronized void registerTool(
            String toolName, AgentTool tool, RegisteredToolFunction registered) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("Tool name cannot be null or blank");
        }
        tools.put(toolName, tool);
        registeredTools.put(toolName, registered);
        version = java.util.UUID.randomUUID().toString();
    }

    synchronized void updatePresetParameters(String name, Map<String, Object> parameters) {
        RegisteredToolFunction registered = registeredTools.get(name);
        if (registered == null) {
            throw new IllegalArgumentException("Tool not found: " + name);
        }
        registered.updatePresetParameters(parameters);
        version = java.util.UUID.randomUUID().toString();
    }

    /**
     * Get tool by name.
     *
     * @param name Tool name
     * @return AgentTool or null if not found
     */
    synchronized AgentTool getTool(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return tools.get(name);
    }

    /**
     * Get registered tool function by name.
     *
     * @param name Tool name
     * @return RegisteredToolFunction or null if not found
     */
    synchronized RegisteredToolFunction getRegisteredTool(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return registeredTools.get(name);
    }

    /**
     * Get all tool names.
     *
     * @return Set of tool names
     */
    synchronized Set<String> getToolNames() {
        return new HashSet<>(tools.keySet());
    }

    /**
     * Get all registered tool functions.
     *
     * @return Map of tool name to RegisteredToolFunction
     */
    synchronized Map<String, RegisteredToolFunction> getAllRegisteredTools() {
        return new ConcurrentHashMap<>(registeredTools);
    }

    /**
     * Remove a tool by name.
     *
     * @param toolName Tool name to remove
     */
    synchronized void removeTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("Tool name cannot be null or blank");
        }
        tools.remove(toolName);
        registeredTools.remove(toolName);
        version = java.util.UUID.randomUUID().toString();
    }

    /**
     * Atomically remove a tool only if the current instance matches the expected one.
     * Uses {@link ConcurrentHashMap#remove(Object, Object)} to avoid TOCTOU races.
     *
     * @param toolName Tool name to remove
     * @param expected The expected AgentTool instance (identity comparison)
     * @return true if the tool was removed, false if it was already replaced or absent
     */
    synchronized boolean removeToolIfSame(String toolName, AgentTool expected) {
        boolean removed = tools.remove(toolName, expected);
        if (removed) {
            registeredTools.remove(toolName);
            version = java.util.UUID.randomUUID().toString();
        }
        return removed;
    }

    /**
     * Remove multiple tools by names.
     *
     * @param toolNames Set of tool names to remove
     */
    synchronized void removeTools(Set<String> toolNames) {
        toolNames.forEach(this::removeTool);
    }

    /**
     * Copy all tools from this registry to another registry.
     *
     * @param target The target registry to copy tools to
     */
    synchronized void copyTo(ToolRegistry target) {
        for (Map.Entry<String, AgentTool> entry : tools.entrySet()) {
            String toolName = entry.getKey();
            AgentTool tool = entry.getValue();
            RegisteredToolFunction registered = registeredTools.get(toolName);
            target.registerTool(
                    toolName,
                    tool,
                    new RegisteredToolFunction(
                            tool,
                            registered.getExtendedModel(),
                            registered.getMcpClientName(),
                            registered.getPresetParameters()));
        }
        target.version = version;
    }
}
