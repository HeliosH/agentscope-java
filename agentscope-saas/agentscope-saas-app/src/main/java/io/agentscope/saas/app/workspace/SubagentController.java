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
package io.agentscope.saas.app.workspace;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import io.agentscope.saas.core.persistence.entity.AgentEntity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Subagent management for the multi-user assistant, backed by {@code subagents/<name>.md} workspace
 * files (no DB entity, no migration). Ported from paw's {@code AgentWorkspaceController} subagent
 * endpoints; the markdown front-matter format and {@link SubagentInfo} shape are identical so the
 * forked frontend works unchanged.
 *
 * <p>All file IO goes through the caller's per-user {@link AbstractFilesystem} (via {@link
 * WorkspaceResolver}), so subagents are isolated per user. The agent-id path variable is
 * org-guarded (404 when absent).
 */
@RestController
@RequestMapping("/api/agents/{agentId}/workspace/subagents")
public class SubagentController {

    private static final Logger log = LoggerFactory.getLogger(SubagentController.class);
    private static final RuntimeContext FS_RC = RuntimeContext.empty();

    private final WorkspaceResolver workspaceResolver;

    public SubagentController(WorkspaceResolver workspaceResolver) {
        this.workspaceResolver = workspaceResolver;
    }

    @GetMapping
    public Mono<List<SubagentInfo>> list(
            @PathVariable String agentId, @AuthenticationPrincipal Jwt jwt) {
        UUID orgId = orgId(jwt);
        return Mono.fromCallable(
                        () -> {
                            AbstractFilesystem fs =
                                    workspaceResolver.resolve(orgId, userId(jwt), agentId);
                            LsResult ls = fs.ls(FS_RC, "/subagents");
                            if (ls == null || !ls.isSuccess() || ls.entries() == null) {
                                return List.<SubagentInfo>of();
                            }
                            List<SubagentInfo> out = new ArrayList<>();
                            for (FileInfo info : ls.entries()) {
                                if (info.isDirectory()) continue;
                                String path = info.path();
                                if (path == null || !path.endsWith(".md")) continue;
                                String name = leafName(path);
                                if (name.isBlank() || "README".equalsIgnoreCase(name)) continue;
                                SubagentInfo si = readSubagent(fs, name);
                                if (si != null) out.add(si);
                            }
                            out.sort(Comparator.comparing(SubagentInfo::name));
                            return out;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/{name}")
    public Mono<SubagentInfo> upsert(
            @PathVariable String agentId,
            @PathVariable String name,
            @RequestBody SubagentUpsertRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        UUID orgId = orgId(jwt);
        return Mono.fromCallable(
                        () -> {
                            validateName(name);
                            if (req == null
                                    || req.description() == null
                                    || req.description().isBlank()) {
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "description is required");
                            }
                            AbstractFilesystem fs =
                                    workspaceResolver.resolve(orgId, userId(jwt), agentId);
                            String markdown = renderSubagentMarkdown(req);
                            writeUtf8(fs, "subagents/" + name + ".md", markdown);
                            return readSubagent(fs, name);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/from-agent")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<SubagentInfo> fromAgent(
            @PathVariable String agentId,
            @RequestBody FromAgentRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        UUID orgId = orgId(jwt);
        return Mono.fromCallable(
                        () -> {
                            if (req == null
                                    || req.sourceAgentId() == null
                                    || req.sourceAgentId().isBlank()) {
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "sourceAgentId is required");
                            }
                            AgentEntity source =
                                    workspaceResolver.requireAgent(orgId, req.sourceAgentId());
                            String name =
                                    (req.name() != null && !req.name().isBlank())
                                            ? req.name()
                                            : req.sourceAgentId();
                            validateName(name);
                            AbstractFilesystem fs =
                                    workspaceResolver.resolve(orgId, userId(jwt), agentId);
                            SubagentUpsertRequest sub =
                                    new SubagentUpsertRequest(
                                            source.getDescription() != null
                                                    ? source.getDescription()
                                                    : source.getName(),
                                            null,
                                            source.getMaxIters(),
                                            null,
                                            "shared",
                                            null,
                                            source.getSysPrompt(),
                                            req.sourceAgentId());
                            writeUtf8(fs, "subagents/" + name + ".md", renderSubagentMarkdown(sub));
                            SubagentInfo info = readSubagent(fs, name);
                            return new SubagentInfo(
                                    info.name(),
                                    info.description(),
                                    info.model(),
                                    info.maxIters(),
                                    info.tools(),
                                    info.workspaceMode(),
                                    info.workspacePath(),
                                    info.hasInlineBody(),
                                    req.sourceAgentId());
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(
            @PathVariable String agentId,
            @PathVariable String name,
            @AuthenticationPrincipal Jwt jwt) {
        UUID orgId = orgId(jwt);
        return Mono.fromRunnable(
                        () -> {
                            validateName(name);
                            AbstractFilesystem fs =
                                    workspaceResolver.resolve(orgId, userId(jwt), agentId);
                            if (!fs.exists(FS_RC, "/subagents/" + name + ".md")) {
                                throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Subagent not found: " + name);
                            }
                            fs.delete(FS_RC, "/subagents/" + name + ".md");
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private static UUID orgId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("org_id"));
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("user_id"));
    }

    private SubagentInfo readSubagent(AbstractFilesystem fs, String name) {
        String markdown = readUtf8(fs, "/subagents/" + name + ".md");
        if (markdown == null) return null;
        try {
            SubagentDeclaration decl = AgentSpecLoader.parse(markdown, name, Path.of("/subagents"));
            return toSubagentInfo(decl);
        } catch (Exception e) {
            log.warn("Failed to parse subagent '{}': {}", name, e.getMessage());
            return null;
        }
    }

    private static SubagentInfo toSubagentInfo(SubagentDeclaration decl) {
        return new SubagentInfo(
                decl.getName(),
                decl.getDescription(),
                decl.getModel(),
                decl.getMaxIters() != 10 ? decl.getMaxIters() : null,
                decl.getTools().isEmpty() ? null : decl.getTools(),
                decl.getWorkspaceMode() == WorkspaceMode.SHARED ? "shared" : "isolated",
                decl.getWorkspacePath() != null ? decl.getWorkspacePath().toString() : null,
                decl.getInlineAgentsBody() != null && !decl.getInlineAgentsBody().isBlank(),
                null);
    }

    static String renderSubagentMarkdown(SubagentUpsertRequest req) {
        StringBuilder sb = new StringBuilder("---\n");
        sb.append("description: ").append(req.description().replace("\n", " ")).append("\n");
        if (req.workspaceMode() != null || req.workspacePath() != null) {
            sb.append("workspace:\n");
            sb.append("  mode: ")
                    .append(req.workspaceMode() != null ? req.workspaceMode() : "isolated")
                    .append("\n");
            if (req.workspacePath() != null && !req.workspacePath().isBlank()) {
                sb.append("  path: ").append(req.workspacePath()).append("\n");
            }
        }
        if (req.model() != null && !req.model().isBlank()) {
            sb.append("model: ").append(req.model()).append("\n");
        }
        if (req.maxIters() != null) {
            sb.append("maxIters: ").append(req.maxIters()).append("\n");
        }
        if (req.tools() != null && !req.tools().isEmpty()) {
            sb.append("tools: [").append(String.join(", ", req.tools())).append("]\n");
        }
        sb.append("---\n");
        if (req.inlineBody() != null && !req.inlineBody().isBlank()) {
            sb.append("\n").append(req.inlineBody().strip()).append("\n");
        }
        return sb.toString();
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Subagent name is required");
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid subagent name: " + name);
        }
    }

    private static String leafName(String absolutePath) {
        if (absolutePath == null || absolutePath.isEmpty()) return "";
        String trimmed =
                absolutePath.endsWith("/")
                        ? absolutePath.substring(0, absolutePath.length() - 1)
                        : absolutePath;
        int dot = trimmed.lastIndexOf('.');
        int slash = trimmed.lastIndexOf('/');
        String withExt = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        return dot > 0 ? withExt.substring(0, withExt.length() - 3) : withExt;
    }

    private static String readUtf8(AbstractFilesystem fs, String absolutePath) {
        ReadResult r = fs.read(FS_RC, absolutePath, 0, Integer.MAX_VALUE);
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

    // -----------------------------------------------------------------
    //  DTOs (paw shapes)
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SubagentInfo(
            String name,
            String description,
            String model,
            Integer maxIters,
            List<String> tools,
            String workspaceMode,
            String workspacePath,
            boolean hasInlineBody,
            String sourceAgentId) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SubagentUpsertRequest(
            String description,
            String model,
            Integer maxIters,
            List<String> tools,
            String workspaceMode,
            String workspacePath,
            String inlineBody,
            String sourceAgentId) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FromAgentRequest(String sourceAgentId, String name) {}
}
