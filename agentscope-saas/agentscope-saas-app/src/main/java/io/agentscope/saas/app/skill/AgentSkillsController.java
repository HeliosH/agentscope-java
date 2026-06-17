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
package io.agentscope.saas.app.skill;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.saas.core.persistence.entity.AgentEntity;
import io.agentscope.saas.core.persistence.repo.AgentRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Skill management for the multi-user assistant (Phase B5′). Lists, reads, creates/edits, and
 * deletes the agent's workspace skills ({@code skills/<name>/SKILL.md} + resources) from the
 * caller's per-user {@link AbstractFilesystem} (resolved via
 * {@link HarnessAgent#workspaceFor(String, String)}), so skills — whether created by the agent
 * during a chat (via {@code SkillManageTool}) or by the user here — are visible and isolated per
 * user when the sandbox is off and Redis is on. The agent-id path variable is validated against
 * the caller's org (404 when absent). Ported from {@code agentscope-builder}'s
 * {@code AgentSkillsController}, dropping the builder-only catalog/guard/marketplace dependencies.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/skills")
public class AgentSkillsController {

    private static final Logger log = LoggerFactory.getLogger(AgentSkillsController.class);
    private static final RuntimeContext FS_RC = RuntimeContext.empty();
    private static final Pattern FRONT_MATTER =
            Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n", Pattern.DOTALL);
    private static final Pattern DESCRIPTION_LINE =
            Pattern.compile("^\\s*description\\s*:\\s*(.+?)\\s*$", Pattern.MULTILINE);
    private static final Pattern NAME_LINE =
            Pattern.compile("^\\s*name\\s*:\\s*(.+?)\\s*$", Pattern.MULTILINE);

    private final HarnessAgent agent;
    private final AgentRepository agentRepository;

    public AgentSkillsController(HarnessAgent agent, AgentRepository agentRepository) {
        this.agent = agent;
        this.agentRepository = agentRepository;
    }

    @GetMapping("/workspace")
    public Mono<List<WorkspaceSkillInfo>> listWorkspaceSkills(
            @PathVariable String agentId, @AuthenticationPrincipal Jwt jwt) {
        UUID orgId = orgId(jwt);
        return Mono.fromCallable(
                        () -> {
                            AbstractFilesystem fs = resolveFilesystem(orgId, userId(jwt), agentId);
                            LsResult ls = fs.ls(null, "/skills");
                            if (ls == null || !ls.isSuccess() || ls.entries() == null) {
                                return List.<WorkspaceSkillInfo>of();
                            }
                            List<WorkspaceSkillInfo> out = new ArrayList<>();
                            for (FileInfo info : ls.entries()) {
                                if (!info.isDirectory()) continue;
                                String dirName = leafName(info.path());
                                if (dirName.isBlank()) continue;
                                WorkspaceSkillInfo skill = readWorkspaceSkill(fs, dirName);
                                if (skill != null) out.add(skill);
                            }
                            out.sort(Comparator.comparing(WorkspaceSkillInfo::name));
                            return out;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/workspace/{name}")
    public Mono<WorkspaceSkillDetail> getWorkspaceSkill(
            @PathVariable String agentId,
            @PathVariable String name,
            @AuthenticationPrincipal Jwt jwt) {
        UUID orgId = orgId(jwt);
        return Mono.fromCallable(
                        () -> {
                            validateSkillName(name);
                            AbstractFilesystem fs = resolveFilesystem(orgId, userId(jwt), agentId);
                            String markdown = readUtf8(fs, "/skills/" + name + "/SKILL.md");
                            if (markdown == null) {
                                throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "SKILL.md missing for: " + name);
                            }
                            Map<String, String> resources = collectResources(fs, name);
                            String description = parseFrontMatterField(markdown, DESCRIPTION_LINE);
                            return new WorkspaceSkillDetail(name, description, markdown, resources);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Creates or overwrites a workspace skill. Writes {@code skills/<name>/SKILL.md} from the
     * request markdown (frontmatter + body) and any optional resource files, then returns the
     * resulting skill info. Overwrites an existing skill of the same name. The write lands in the
     * caller's per-user namespace, so it is visible to the agent during that user's chats.
     */
    @PutMapping("/workspace/{name}")
    public Mono<WorkspaceSkillInfo> upsertWorkspaceSkill(
            @PathVariable String agentId,
            @PathVariable String name,
            @RequestBody WorkspaceSkillUpsertRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        UUID orgId = orgId(jwt);
        return Mono.fromCallable(
                        () -> {
                            validateSkillName(name);
                            if (req == null || req.markdown() == null || req.markdown().isBlank()) {
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "markdown is required");
                            }
                            AbstractFilesystem fs = resolveFilesystem(orgId, userId(jwt), agentId);
                            writeUtf8(fs, "skills/" + name + "/SKILL.md", req.markdown());
                            if (req.resources() != null) {
                                for (Map.Entry<String, String> e : req.resources().entrySet()) {
                                    String key = e.getKey();
                                    if (key == null || key.isBlank()) continue;
                                    String safe = sanitiseRelativePath(key);
                                    writeUtf8(
                                            fs,
                                            "skills/" + name + "/" + safe,
                                            e.getValue() != null ? e.getValue() : "");
                                }
                            }
                            return readWorkspaceSkill(fs, name);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/workspace/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteWorkspaceSkill(
            @PathVariable String agentId,
            @PathVariable String name,
            @AuthenticationPrincipal Jwt jwt) {
        UUID orgId = orgId(jwt);
        return Mono.fromRunnable(
                        () -> {
                            validateSkillName(name);
                            AbstractFilesystem fs = resolveFilesystem(orgId, userId(jwt), agentId);
                            if (!fs.exists(null, "/skills/" + name)) {
                                throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Skill not found: " + name);
                            }
                            fs.delete(null, "/skills/" + name);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
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
            log.debug("skill access org={} user={} agent={}", orgId, userId, a.getName());
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
    //  Skill parsing helpers
    // -----------------------------------------------------------------

    private static WorkspaceSkillInfo readWorkspaceSkill(AbstractFilesystem fs, String dirName) {
        String content = readUtf8(fs, "/skills/" + dirName + "/SKILL.md");
        if (content == null) return null;
        String description = parseFrontMatterField(content, DESCRIPTION_LINE);
        String name = parseFrontMatterField(content, NAME_LINE);
        if (name == null || name.isBlank()) {
            name = dirName;
        }
        SkillSize size = computeSize(fs, dirName);
        return new WorkspaceSkillInfo(
                dirName,
                name,
                description,
                size.totalBytes(),
                size.resourceCount(),
                fs.exists(null, "/skills/" + dirName + "/references"),
                fs.exists(null, "/skills/" + dirName + "/scripts"),
                "custom");
    }

    private static Map<String, String> collectResources(AbstractFilesystem fs, String dirName) {
        Map<String, String> out = new LinkedHashMap<>();
        walk(
                fs,
                "/skills/" + dirName,
                "/skills/" + dirName + "/",
                (relativePath, absolutePath) -> {
                    if (relativePath.equals("SKILL.md")) {
                        return;
                    }
                    String content = readUtf8(fs, absolutePath);
                    out.put(relativePath, content != null ? content : "");
                });
        return out;
    }

    private static SkillSize computeSize(AbstractFilesystem fs, String dirName) {
        long[] total = new long[] {0L};
        int[] count = new int[] {0};
        walk(
                fs,
                "/skills/" + dirName,
                "/skills/" + dirName + "/",
                (relativePath, absolutePath) -> {
                    total[0] += fileSize(fs, absolutePath);
                    if (!relativePath.equals("SKILL.md")) count[0]++;
                });
        return new SkillSize(total[0], count[0]);
    }

    @FunctionalInterface
    private interface FileVisitor {
        void visit(String relativePath, String absolutePath);
    }

    private static void walk(
            AbstractFilesystem fs, String rootAbs, String relativeBase, FileVisitor visitor) {
        LsResult ls = fs.ls(null, rootAbs);
        if (ls == null || !ls.isSuccess() || ls.entries() == null) return;
        for (FileInfo info : ls.entries()) {
            String abs = info.path();
            String name = leafName(abs);
            if (name.isBlank()) continue;
            if (info.isDirectory()) {
                walk(fs, abs, relativeBase, visitor);
            } else {
                String rel =
                        abs.startsWith(relativeBase) ? abs.substring(relativeBase.length()) : name;
                visitor.visit(rel, abs);
            }
        }
    }

    private static long fileSize(AbstractFilesystem fs, String absolutePath) {
        int slash = absolutePath.lastIndexOf('/');
        if (slash <= 0) return 0L;
        String parent = absolutePath.substring(0, slash);
        String name = absolutePath.substring(slash + 1);
        LsResult ls = fs.ls(null, parent);
        if (ls == null || !ls.isSuccess() || ls.entries() == null) return 0L;
        for (FileInfo info : ls.entries()) {
            if (leafName(info.path()).equals(name)) return info.size();
        }
        return 0L;
    }

    private static String readUtf8(AbstractFilesystem fs, String absolutePath) {
        ReadResult r = fs.read(null, absolutePath, 0, Integer.MAX_VALUE);
        if (r == null || !r.isSuccess() || r.fileData() == null) return null;
        return r.fileData().content();
    }

    /** Writes {@code content} to {@code absPath} (workspace-relative or leading-slash) via uploadFiles. */
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

    /** Normalises a resource path to workspace-relative form, rejecting traversal. */
    private static String sanitiseRelativePath(String relative) {
        if (relative == null || relative.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Resource path is required");
        }
        String normalized = relative.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isEmpty() || normalized.contains("..")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid resource path: " + relative);
        }
        return normalized;
    }

    private static String parseFrontMatterField(String markdown, Pattern fieldPattern) {
        if (markdown == null) return null;
        Matcher m = FRONT_MATTER.matcher(markdown);
        if (!m.find()) return null;
        Matcher f = fieldPattern.matcher(m.group(1));
        if (!f.find()) return null;
        String value = f.group(1).trim();
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static void validateSkillName(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill name is required");
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid skill name: " + name);
        }
    }

    /** Strips a single trailing slash (LocalFilesystem appends one to directory entries). */
    private static String leafName(String absolutePath) {
        if (absolutePath == null || absolutePath.isEmpty()) return "";
        String trimmed =
                absolutePath.endsWith("/")
                        ? absolutePath.substring(0, absolutePath.length() - 1)
                        : absolutePath;
        if (trimmed.isEmpty()) return "";
        int slash = trimmed.lastIndexOf('/');
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }

    private record SkillSize(long totalBytes, int resourceCount) {}

    // -----------------------------------------------------------------
    //  View DTOs
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkspaceSkillInfo(
            String dirName,
            String name,
            String description,
            long sizeBytes,
            int resourceCount,
            boolean hasReferences,
            boolean hasScripts,
            String origin) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkspaceSkillDetail(
            String name, String description, String markdown, Map<String, String> resources) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkspaceSkillUpsertRequest(String markdown, Map<String, String> resources) {}
}
