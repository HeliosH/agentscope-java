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
package io.agentscope.saas.app.tools;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.agentscope.saas.core.persistence.entity.AgentEntity;
import io.agentscope.saas.core.persistence.repo.AgentRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Tools management for an agent in the multi-tenant SaaS (Phase F5). Surfaces the agent's
 * {@code tools.json} (read/overwrite), a config-derived live tool list, and the built-in / MCP
 * catalogs for the UI picker.
 *
 * <p>Unlike the desktop {@code agentscope-paw} variant (which builds a transient {@link
 * HarnessAgent} against a local workspace path to introspect {@code Toolkit.getToolSchemas()}),
 * this controller resolves the caller's per-user {@link AbstractFilesystem} via {@link
 * HarnessAgent#workspaceFor(String, String)} and derives the active tool list from {@code
 * tools.json} directly. Live toolkit introspection would require spinning up a sandbox per request
 * and is deferred; the config-derived view is tenant-isolated and sufficient for the console.
 *
 * <p>The agent-id path variable is validated against the caller's org (404 when absent), so a user
 * can only manage tools for agents they own within their tenant.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/tools")
public class AgentToolsController {

    private static final Logger log = LoggerFactory.getLogger(AgentToolsController.class);
    private static final RuntimeContext FS_RC = RuntimeContext.empty();
    private static final String TOOLS_JSON = "tools.json";

    /**
     * Canonical list of harness built-in tools. Mirrors the registrations performed in {@code
     * HarnessAgent.Builder.build()}. Used both for source attribution on {@code /active} and as
     * the {@code /catalog/builtins} response.
     */
    private static final List<BuiltinToolInfo> BUILTIN_TOOLS =
            List.of(
                    new BuiltinToolInfo(
                            "read_file", "Read a file from the workspace.", "filesystem"),
                    new BuiltinToolInfo(
                            "write_file",
                            "Write or overwrite a file in the workspace.",
                            "filesystem"),
                    new BuiltinToolInfo(
                            "edit_file", "Apply a diff to an existing file.", "filesystem"),
                    new BuiltinToolInfo(
                            "list_files", "List files under a directory.", "filesystem"),
                    new BuiltinToolInfo(
                            "grep_files", "Search file contents with a regex.", "filesystem"),
                    new BuiltinToolInfo(
                            "glob_files", "Find files matching a glob pattern.", "filesystem"),
                    new BuiltinToolInfo(
                            "memory_search",
                            "Semantic search across the agent's long-term memory.",
                            "memory"),
                    new BuiltinToolInfo(
                            "memory_get", "Fetch a specific memory entry by id.", "memory"),
                    new BuiltinToolInfo(
                            "session_search",
                            "Search prior session transcripts for context.",
                            "memory"),
                    new BuiltinToolInfo(
                            "execute",
                            "Execute a shell command (sandbox / local-shell modes only).",
                            "shell"));

    private static final Set<String> BUILTIN_NAMES;

    static {
        BUILTIN_NAMES = new HashSet<>();
        for (BuiltinToolInfo b : BUILTIN_TOOLS) BUILTIN_NAMES.add(b.id());
    }

    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .enable(SerializationFeature.INDENT_OUTPUT);

    private final HarnessAgent agent;
    private final AgentRepository agentRepository;
    private final List<McpCatalogEntry> mcpCatalog;

    public AgentToolsController(HarnessAgent agent, AgentRepository agentRepository) {
        this.agent = agent;
        this.agentRepository = agentRepository;
        this.mcpCatalog = loadMcpCatalog();
    }

    /**
     * Config-derived live tool list. Built-ins are filtered by the workspace {@code tools.json}
     * {@code allow}/{@code deny} lists; MCP servers are listed from the same config. Comment-keys
     * in a hand-edited {@code tools.json} are not round-tripped through the UI write path.
     */
    @GetMapping("/active")
    public Mono<ActiveToolsResponse> active(
            @PathVariable String agentId, @AuthenticationPrincipal Jwt jwt) {
        UUID orgId = orgId(jwt);
        return Mono.fromCallable(
                        () -> {
                            AbstractFilesystem fs = resolveFilesystem(orgId, userId(jwt), agentId);
                            return configDerivedView(fs);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/config")
    public Mono<ToolsConfig> getConfig(
            @PathVariable String agentId, @AuthenticationPrincipal Jwt jwt) {
        UUID orgId = orgId(jwt);
        return Mono.fromCallable(
                        () -> {
                            AbstractFilesystem fs = resolveFilesystem(orgId, userId(jwt), agentId);
                            ToolsConfig cfg = readConfig(fs);
                            return cfg != null ? cfg : new ToolsConfig();
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/config")
    public Mono<ToolsConfig> putConfig(
            @PathVariable String agentId,
            @RequestBody ToolsConfig body,
            @AuthenticationPrincipal Jwt jwt) {
        UUID orgId = orgId(jwt);
        return Mono.fromCallable(
                        () -> {
                            if (body == null) {
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "Request body is required");
                            }
                            validate(body);
                            AbstractFilesystem fs = resolveFilesystem(orgId, userId(jwt), agentId);
                            String json;
                            try {
                                json = MAPPER.writeValueAsString(body);
                            } catch (IOException e) {
                                throw new ResponseStatusException(
                                        HttpStatus.INTERNAL_SERVER_ERROR,
                                        "Failed to serialize tools.json: " + e.getMessage());
                            }
                            writeUtf8(fs, TOOLS_JSON, json + "\n");
                            return body;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/catalog/builtins")
    public Mono<List<BuiltinToolInfo>> catalogBuiltins(
            @PathVariable String agentId, @AuthenticationPrincipal Jwt jwt) {
        return Mono.just(BUILTIN_TOOLS);
    }

    @GetMapping("/catalog/mcp-servers")
    public Mono<List<McpCatalogEntry>> catalogMcpServers(
            @PathVariable String agentId, @AuthenticationPrincipal Jwt jwt) {
        return Mono.just(mcpCatalog);
    }

    // -----------------------------------------------------------------
    //  Tenant resolution
    // -----------------------------------------------------------------

    private AbstractFilesystem resolveFilesystem(UUID orgId, UUID userId, String agentId) {
        try {
            UUID id = UUID.fromString(agentId);
            AgentEntity a =
                    agentRepository
                            .findByIdAndOrgId(id, orgId)
                            .orElseThrow(
                                    () ->
                                            new ResponseStatusException(
                                                    HttpStatus.NOT_FOUND,
                                                    "Agent not found: " + agentId));
            log.debug("tools access org={} user={} agent={}", orgId, userId, a.getName());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: " + agentId);
        }
        return agent.workspaceFor(userId.toString(), null).getFilesystem();
    }

    private static UUID orgId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("org_id"));
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("user_id"));
    }

    // -----------------------------------------------------------------
    //  tools.json read / derive
    // -----------------------------------------------------------------

    private static ActiveToolsResponse configDerivedView(AbstractFilesystem fs) {
        ToolsConfig cfg = readConfig(fs);
        List<ActiveTool> tools = new ArrayList<>();
        Set<String> deny =
                cfg != null && cfg.getDeny() != null ? new HashSet<>(cfg.getDeny()) : Set.of();
        Set<String> allow =
                cfg != null && cfg.getAllow() != null && !cfg.getAllow().isEmpty()
                        ? new HashSet<>(cfg.getAllow())
                        : null;
        for (BuiltinToolInfo b : BUILTIN_TOOLS) {
            if (deny.contains(b.id())) continue;
            if (allow != null && !allow.contains(b.id())) continue;
            tools.add(new ActiveTool(b.id(), b.description(), "built-in"));
        }
        if (cfg != null && cfg.getMcpServers() != null) {
            for (Map.Entry<String, McpServerConfig> e : cfg.getMcpServers().entrySet()) {
                tools.add(
                        new ActiveTool(
                                e.getKey(),
                                "MCP server (" + e.getValue().getTransport() + ")",
                                "mcp"));
            }
        }
        return new ActiveToolsResponse(tools, List.of());
    }

    private static ToolsConfig readConfig(AbstractFilesystem fs) {
        String raw = readUtf8(fs, "/" + TOOLS_JSON);
        if (raw == null || raw.isBlank()) return null;
        try {
            return MAPPER.readValue(raw, ToolsConfig.class);
        } catch (Exception e) {
            log.debug("tools.json unreadable: {}", e.getMessage());
            return null;
        }
    }

    private static void validate(ToolsConfig cfg) {
        if (cfg.getAllow() != null) {
            for (String n : cfg.getAllow()) {
                if (n == null || n.isBlank()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "allow contains a blank entry");
                }
            }
        }
        if (cfg.getDeny() != null) {
            for (String n : cfg.getDeny()) {
                if (n == null || n.isBlank()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "deny contains a blank entry");
                }
            }
        }
        if (cfg.getMcpServers() != null) {
            for (Map.Entry<String, McpServerConfig> e : cfg.getMcpServers().entrySet()) {
                String name = e.getKey();
                McpServerConfig s = e.getValue();
                if (name == null || name.isBlank()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "mcpServers contains a blank server name");
                }
                if (s == null) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "mcpServers." + name + " is null");
                }
                String t = s.getTransport();
                if (t == null || t.isBlank()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "mcpServers." + name + ": transport is required");
                }
                String tl = t.toLowerCase(Locale.ROOT);
                switch (tl) {
                    case "stdio" -> {
                        if (s.getCommand() == null || s.getCommand().isBlank()) {
                            throw new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST,
                                    "mcpServers." + name + ": stdio requires 'command'");
                        }
                    }
                    case "sse", "http", "streamable-http", "streamablehttp" -> {
                        if (s.getUrl() == null || s.getUrl().isBlank()) {
                            throw new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST,
                                    "mcpServers." + name + ": " + tl + " requires 'url'");
                        }
                    }
                    default ->
                            throw new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST,
                                    "mcpServers."
                                            + name
                                            + ": unsupported transport '"
                                            + t
                                            + "' (expected stdio, sse, http)");
                }
            }
        }
    }

    // -----------------------------------------------------------------
    //  Filesystem helpers (mirror AgentSkillsController)
    // -----------------------------------------------------------------

    private static String readUtf8(AbstractFilesystem fs, String absolutePath) {
        ReadResult r = fs.read(null, absolutePath, 0, Integer.MAX_VALUE);
        if (r == null || !r.isSuccess() || r.fileData() == null) return null;
        return r.fileData().content();
    }

    private static void writeUtf8(AbstractFilesystem fs, String absPath, String content) {
        String rel = absPath.startsWith("/") ? absPath.substring(1) : absPath;
        List<FileUploadResponse> ur =
                fs.uploadFiles(
                        FS_RC, List.of(Map.entry(rel, content.getBytes(StandardCharsets.UTF_8))));
        if (ur.isEmpty() || !ur.get(0).isSuccess()) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to write "
                            + absPath
                            + ": "
                            + (ur.isEmpty() ? "no response" : ur.get(0).error()));
        }
    }

    private static List<McpCatalogEntry> loadMcpCatalog() {
        ClassPathResource r = new ClassPathResource("catalog/mcp-servers.json");
        if (!r.exists()) {
            log.warn("catalog/mcp-servers.json not found on classpath; MCP catalog is empty.");
            return List.of();
        }
        try (InputStream in = r.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            String json = new String(bytes, StandardCharsets.UTF_8);
            McpCatalogEntry[] arr = MAPPER.readValue(json, McpCatalogEntry[].class);
            return List.of(arr);
        } catch (Exception e) {
            log.warn("Failed to load catalog/mcp-servers.json: {}", e.getMessage());
            return List.of();
        }
    }

    // -----------------------------------------------------------------
    //  DTOs
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActiveTool(String name, String description, String source) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ActiveToolsResponse(List<ActiveTool> tools, List<String> warnings) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BuiltinToolInfo(String id, String description, String group) {}

    /**
     * Bundled MCP server template loaded from {@code classpath:catalog/mcp-servers.json}. Mirrors
     * {@link McpServerConfig} plus UI metadata ({@code id}, {@code name}, {@code description},
     * {@code requiredEnv}, {@code docsUrl}).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record McpCatalogEntry(
            String id,
            String name,
            String description,
            String transport,
            String url,
            String command,
            List<String> args,
            Map<String, String> env,
            Map<String, String> headers,
            Map<String, String> queryParams,
            List<String> requiredEnv,
            String docsUrl) {}
}
