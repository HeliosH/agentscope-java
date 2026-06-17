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
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.saas.core.persistence.entity.AgentEntity;
import io.agentscope.saas.core.persistence.repo.AgentRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Workspace file CRUD for the multi-user assistant (Phase B5′). Every operation resolves the
 * caller's per-user {@link AbstractFilesystem} via {@link HarnessAgent#workspaceFor(String, String)}
 * — so when the sandbox is off and Redis is on, files land in the user's isolated
 * {@code RemoteFilesystemSpec} namespace (MEMORY.md, skills/, memory/, …); no Docker/FUSE needed.
 * The agent-id path variable is validated against the caller's org (404 when absent) purely as a
 * tenant guard — the workspace itself is keyed by userId, shared across an agent's sessions.
 *
 * <p>All paths are workspace-relative. File IO goes through {@link AbstractFilesystem} (ls / read /
 * glob / uploadFiles / move / delete), which honors per-user namespacing automatically. Ported from
 * {@code agentscope-builder}'s {@code AgentWorkspaceController}, dropping the builder-only
 * catalog/guard/audit dependencies in favor of the SaaS tenant pattern.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/workspace")
public class AgentWorkspaceController {

    private static final Logger log = LoggerFactory.getLogger(AgentWorkspaceController.class);
    private static final int MAX_FILE_SIZE = 512 * 1024;
    private static final RuntimeContext FS_RC = RuntimeContext.empty();

    private final HarnessAgent agent;
    private final AgentRepository agentRepository;

    public AgentWorkspaceController(HarnessAgent agent, AgentRepository agentRepository) {
        this.agent = agent;
        this.agentRepository = agentRepository;
    }

    // -----------------------------------------------------------------
    //  Summary + memory
    // -----------------------------------------------------------------

    @GetMapping
    public Mono<WorkspaceSummary> summary(
            @PathVariable String agentId, @AuthenticationPrincipal Jwt jwt) {
        UUID orgId = orgId(jwt);
        return Mono.fromCallable(
                        () -> {
                            AbstractFilesystem fs = resolveFilesystem(orgId, userId(jwt), agentId);
                            boolean agentsMdExists = fs.exists(FS_RC, "/AGENTS.md");
                            boolean memoryMdExists = fs.exists(FS_RC, "/MEMORY.md");
                            int skillCount = countLs(fs, "/skills", true, null);
                            int subagentCount = countLs(fs, "/subagents", false, ".md");
                            int dailyMemoryCount = countLs(fs, "/memory", false, ".md");
                            boolean exists =
                                    agentsMdExists
                                            || memoryMdExists
                                            || skillCount > 0
                                            || subagentCount > 0
                                            || dailyMemoryCount > 0;
                            return new WorkspaceSummary(
                                    agentId,
                                    exists,
                                    agentsMdExists,
                                    memoryMdExists,
                                    skillCount,
                                    subagentCount,
                                    dailyMemoryCount);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/memory")
    public Mono<MemoryView> memory(@PathVariable String agentId, @AuthenticationPrincipal Jwt jwt) {
        UUID orgId = orgId(jwt);
        return Mono.fromCallable(
                        () -> {
                            AbstractFilesystem fs = resolveFilesystem(orgId, userId(jwt), agentId);
                            String memoryContent = null;
                            if (fs.exists(FS_RC, "MEMORY.md")) {
                                ReadResult rr = fs.read(FS_RC, "MEMORY.md", 0, 50000);
                                if (rr.isSuccess() && rr.fileData() != null) {
                                    memoryContent = rr.fileData().content();
                                }
                            }
                            List<DailyMemoryFile> dailyFiles = new ArrayList<>();
                            LsResult ls = fs.ls(FS_RC, "/memory");
                            if (ls.isSuccess() && ls.entries() != null) {
                                ls.entries().stream()
                                        .filter(
                                                fi ->
                                                        !fi.isDirectory()
                                                                && fi.path().endsWith(".md"))
                                        .sorted(Comparator.comparing(FileInfo::path).reversed())
                                        .forEach(
                                                fi ->
                                                        dailyFiles.add(
                                                                new DailyMemoryFile(
                                                                        fileName(fi.path()),
                                                                        fi.size())));
                            }
                            return new MemoryView(memoryContent, dailyFiles);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    // -----------------------------------------------------------------
    //  File tree + read/write/create/move/delete
    // -----------------------------------------------------------------

    @GetMapping("/files")
    public Mono<List<FileNode>> tree(
            @PathVariable String agentId,
            @RequestParam(name = "recursive", defaultValue = "true") boolean recursive,
            @AuthenticationPrincipal Jwt jwt) {
        UUID orgId = orgId(jwt);
        return Mono.fromCallable(
                        () -> {
                            AbstractFilesystem fs = resolveFilesystem(orgId, userId(jwt), agentId);
                            if (recursive) {
                                GlobResult gr = fs.glob(FS_RC, "**/*", "/");
                                if (!gr.isSuccess() || gr.matches() == null) {
                                    return List.<FileNode>of();
                                }
                                return buildTreeFromGlob(gr.matches());
                            }
                            LsResult ls = fs.ls(FS_RC, "/");
                            if (!ls.isSuccess() || ls.entries() == null) {
                                return List.<FileNode>of();
                            }
                            return flattenShallow(ls.entries());
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/file")
    public Mono<String> readFile(
            @PathVariable String agentId,
            @RequestParam("path") String path,
            @AuthenticationPrincipal Jwt jwt) {
        UUID orgId = orgId(jwt);
        return Mono.fromCallable(
                        () -> {
                            AbstractFilesystem fs = resolveFilesystem(orgId, userId(jwt), agentId);
                            String abs = toAbsFsPath(path);
                            if (!fs.exists(FS_RC, abs)) {
                                throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "File not found: " + path);
                            }
                            ReadResult rr = fs.read(FS_RC, abs, 0, Integer.MAX_VALUE);
                            if (!rr.isSuccess() || rr.fileData() == null) {
                                throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "File not found: " + path);
                            }
                            String content = rr.fileData().content();
                            String encoding = rr.fileData().encoding();
                            if (content == null) {
                                return "";
                            }
                            if ("base64".equalsIgnoreCase(encoding)) {
                                return "(binary file: "
                                        + content.length()
                                        + " base64 chars; not editable)";
                            }
                            if (content.length() > MAX_FILE_SIZE) {
                                return "(file too large to display: "
                                        + content.length()
                                        + " bytes)";
                            }
                            return content;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/file")
    public Mono<FileNode> writeFile(
            @PathVariable String agentId,
            @RequestParam("path") String path,
            @RequestBody WriteRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        UUID orgId = orgId(jwt);
        return Mono.fromCallable(
                        () -> {
                            AbstractFilesystem fs = resolveFilesystem(orgId, userId(jwt), agentId);
                            String abs = toAbsFsPath(path);
                            String rel = toRelFsPath(path);
                            if (isExistingDirectory(fs, abs)) {
                                throw new ResponseStatusException(
                                        HttpStatus.CONFLICT, "Path is a directory: " + path);
                            }
                            boolean existed = fs.exists(FS_RC, abs);
                            String content =
                                    req != null && req.content() != null ? req.content() : "";
                            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                            List<FileUploadResponse> ur =
                                    fs.uploadFiles(FS_RC, List.of(Map.entry(rel, bytes)));
                            if (ur.isEmpty() || !ur.get(0).isSuccess()) {
                                String err = ur.isEmpty() ? "no response" : ur.get(0).error();
                                throw new ResponseStatusException(
                                        HttpStatus.INTERNAL_SERVER_ERROR,
                                        "Failed to write file: " + err);
                            }
                            return new FileNode(
                                    basename(rel), rel, "file", (long) bytes.length, null);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/file")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<FileNode> createNode(
            @PathVariable String agentId,
            @RequestParam("path") String path,
            @RequestParam(name = "type", defaultValue = "file") String type,
            @AuthenticationPrincipal Jwt jwt) {
        UUID orgId = orgId(jwt);
        return Mono.fromCallable(
                        () -> {
                            if ("dir".equalsIgnoreCase(type)) {
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Empty directory creation is not supported — create a file"
                                                + " inside the directory instead.");
                            }
                            AbstractFilesystem fs = resolveFilesystem(orgId, userId(jwt), agentId);
                            String abs = toAbsFsPath(path);
                            String rel = toRelFsPath(path);
                            if (fs.exists(FS_RC, abs)
                                    || fs.exists(FS_RC, abs.endsWith("/") ? abs : abs + "/")) {
                                throw new ResponseStatusException(
                                        HttpStatus.CONFLICT, "Already exists: " + path);
                            }
                            List<FileUploadResponse> ur =
                                    fs.uploadFiles(FS_RC, List.of(Map.entry(rel, new byte[0])));
                            if (ur.isEmpty() || !ur.get(0).isSuccess()) {
                                String err = ur.isEmpty() ? "no response" : ur.get(0).error();
                                throw new ResponseStatusException(
                                        HttpStatus.INTERNAL_SERVER_ERROR,
                                        "Failed to create file: " + err);
                            }
                            return new FileNode(basename(rel), rel, "file", 0L, null);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/file/move")
    public Mono<FileNode> moveNode(
            @PathVariable String agentId,
            @RequestBody MoveRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        UUID orgId = orgId(jwt);
        return Mono.fromCallable(
                        () -> {
                            if (req == null || req.from() == null || req.to() == null) {
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "from and to are required");
                            }
                            AbstractFilesystem fs = resolveFilesystem(orgId, userId(jwt), agentId);
                            String absFrom = toAbsFsPath(req.from());
                            String absTo = toAbsFsPath(req.to());
                            if (!fs.exists(FS_RC, absFrom)) {
                                throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Source not found: " + req.from());
                            }
                            if (fs.exists(FS_RC, absTo)
                                    || fs.exists(
                                            FS_RC, absTo.endsWith("/") ? absTo : absTo + "/")) {
                                throw new ResponseStatusException(
                                        HttpStatus.CONFLICT, "Target already exists: " + req.to());
                            }
                            WriteResult wr = fs.move(FS_RC, absFrom, absTo);
                            if (!wr.isSuccess()) {
                                throw new ResponseStatusException(
                                        HttpStatus.INTERNAL_SERVER_ERROR,
                                        "Move failed: " + wr.error());
                            }
                            String relTo = toRelFsPath(req.to());
                            return new FileNode(basename(relTo), relTo, "file", null, null);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/file")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteNode(
            @PathVariable String agentId,
            @RequestParam("path") String path,
            @AuthenticationPrincipal Jwt jwt) {
        UUID orgId = orgId(jwt);
        return Mono.fromRunnable(
                        () -> {
                            AbstractFilesystem fs = resolveFilesystem(orgId, userId(jwt), agentId);
                            String abs = toAbsFsPath(path);
                            String absDir = abs.endsWith("/") ? abs : abs + "/";
                            if (!fs.exists(FS_RC, abs) && !fs.exists(FS_RC, absDir)) {
                                throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Not found: " + path);
                            }
                            WriteResult wr = fs.delete(FS_RC, abs);
                            if (!wr.isSuccess()) {
                                throw new ResponseStatusException(
                                        HttpStatus.INTERNAL_SERVER_ERROR,
                                        "Delete failed: " + wr.error());
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    // -----------------------------------------------------------------
    //  Tenant resolution
    // -----------------------------------------------------------------

    /**
     * Validates that {@code agentId} belongs to the caller's org, then returns the caller's per-user
     * filesystem. 404 (not 403) when the agent is absent so existence is not leaked.
     */
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
            log.debug("workspace access org={} user={} agent={}", orgId, userId, a.getName());
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

    private static boolean existed(WriteRequest req) {
        return req != null && req.content() != null;
    }

    // -----------------------------------------------------------------
    //  Path + tree helpers
    // -----------------------------------------------------------------

    private static String toAbsFsPath(String userPath) {
        String p = userPath == null ? "" : userPath.trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.isEmpty()) {
            return "/";
        }
        String abs = "/" + p;
        try {
            AbstractFilesystem.validatePath(abs);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return abs;
    }

    private static String toRelFsPath(String userPath) {
        String abs = toAbsFsPath(userPath);
        if ("/".equals(abs)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Path is required for this operation");
        }
        return abs.substring(1);
    }

    private static String basename(String relPath) {
        int slash = relPath.lastIndexOf('/');
        return slash >= 0 ? relPath.substring(slash + 1) : relPath;
    }

    /**
     * True iff {@code absPath} exists <em>and</em> is a directory. Used to reject overwriting a
     * directory with a file: a plain {@code fs.exists(path)} returns true for both files and dirs
     * on the composite/remote filesystem, so we confirm via the parent {@code ls} entry's
     * {@code isDirectory} flag.
     */
    private static boolean isExistingDirectory(AbstractFilesystem fs, String absPath) {
        if (!fs.exists(FS_RC, absPath)) {
            return false;
        }
        int slash = absPath.lastIndexOf('/');
        String parent = slash <= 0 ? "/" : absPath.substring(0, slash);
        String leaf = slash < 0 ? absPath : absPath.substring(slash + 1);
        LsResult ls = fs.ls(FS_RC, parent);
        if (!ls.isSuccess() || ls.entries() == null) {
            return false;
        }
        for (FileInfo fi : ls.entries()) {
            if (fileName(fi.path()).equals(leaf)) {
                return fi.isDirectory();
            }
        }
        return false;
    }

    private static String fileName(String path) {
        return basename(relPath(path));
    }

    private static String relPath(String fsPath) {
        if (fsPath == null) {
            return "";
        }
        String p = fsPath;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static int countLs(
            AbstractFilesystem fs, String dirAbsPath, boolean dirOnly, String suffix) {
        LsResult ls = fs.ls(FS_RC, dirAbsPath);
        if (!ls.isSuccess() || ls.entries() == null) {
            return 0;
        }
        int n = 0;
        for (FileInfo fi : ls.entries()) {
            if (dirOnly) {
                if (fi.isDirectory()) {
                    n++;
                }
            } else if (!fi.isDirectory()) {
                if (suffix == null || fi.path().endsWith(suffix)) {
                    n++;
                }
            }
        }
        return n;
    }

    private static List<FileNode> buildTreeFromGlob(List<FileInfo> files) {
        Map<String, List<FileNode>> rootChildren = new LinkedHashMap<>();
        Map<String, List<FileNode>> dirChildren = new LinkedHashMap<>();
        Map<String, FileNode> dirNodes = new LinkedHashMap<>();
        Set<String> dirPaths = new LinkedHashSet<>();

        for (FileInfo fi : files) {
            String rel = relPath(fi.path());
            if (rel.isEmpty()) {
                continue;
            }
            String[] parts = rel.split("/");
            if (fi.isDirectory()) {
                ensureDirChain(parts, parts.length, dirPaths, dirNodes, rootChildren, dirChildren);
                continue;
            }
            ensureDirChain(parts, parts.length - 1, dirPaths, dirNodes, rootChildren, dirChildren);
            FileNode fileNode = new FileNode(parts[parts.length - 1], rel, "file", fi.size(), null);
            if (parts.length == 1) {
                rootChildren.computeIfAbsent("", k -> new ArrayList<>()).add(fileNode);
            } else {
                String parent = String.join("/", Arrays.copyOf(parts, parts.length - 1));
                dirChildren.computeIfAbsent(parent, k -> new ArrayList<>()).add(fileNode);
            }
        }

        for (Map.Entry<String, FileNode> e : dirNodes.entrySet()) {
            List<FileNode> kids = dirChildren.getOrDefault(e.getKey(), new ArrayList<>());
            sortNodes(kids);
            FileNode populated =
                    new FileNode(e.getValue().name(), e.getValue().path(), "dir", null, kids);
            replaceInParent(e.getKey(), populated, rootChildren, dirChildren);
        }

        List<FileNode> roots = rootChildren.getOrDefault("", new ArrayList<>());
        sortNodes(roots);
        return roots;
    }

    private static void ensureDirChain(
            String[] parts,
            int len,
            Set<String> dirPaths,
            Map<String, FileNode> dirNodes,
            Map<String, List<FileNode>> rootChildren,
            Map<String, List<FileNode>> dirChildren) {
        for (int i = 1; i <= len; i++) {
            String dirPath = String.join("/", Arrays.copyOf(parts, i));
            if (!dirPaths.add(dirPath)) {
                continue;
            }
            FileNode placeholder = new FileNode(parts[i - 1], dirPath, "dir", null, null);
            dirNodes.put(dirPath, placeholder);
            if (i == 1) {
                rootChildren.computeIfAbsent("", k -> new ArrayList<>()).add(placeholder);
            } else {
                String parent = String.join("/", Arrays.copyOf(parts, i - 1));
                dirChildren.computeIfAbsent(parent, k -> new ArrayList<>()).add(placeholder);
            }
        }
    }

    private static void replaceInParent(
            String dirPath,
            FileNode populated,
            Map<String, List<FileNode>> rootChildren,
            Map<String, List<FileNode>> dirChildren) {
        int slash = dirPath.lastIndexOf('/');
        List<FileNode> parentList;
        if (slash < 0) {
            parentList = rootChildren.get("");
        } else {
            parentList = dirChildren.get(dirPath.substring(0, slash));
        }
        if (parentList == null) {
            return;
        }
        for (int i = 0; i < parentList.size(); i++) {
            if (parentList.get(i).path().equals(dirPath)) {
                parentList.set(i, populated);
                return;
            }
        }
    }

    private static List<FileNode> flattenShallow(List<FileInfo> entries) {
        Map<String, FileNode> seen = new LinkedHashMap<>();
        for (FileInfo fi : entries) {
            String rel = relPath(fi.path());
            if (rel.isEmpty()) {
                continue;
            }
            seen.putIfAbsent(
                    rel,
                    new FileNode(
                            basename(rel),
                            rel,
                            fi.isDirectory() ? "dir" : "file",
                            fi.isDirectory() ? null : fi.size(),
                            null));
        }
        List<FileNode> out = new ArrayList<>(seen.values());
        sortNodes(out);
        return out;
    }

    private static void sortNodes(List<FileNode> nodes) {
        nodes.sort(
                Comparator.<FileNode, Integer>comparing(n -> "dir".equals(n.type()) ? 0 : 1)
                        .thenComparing(FileNode::name));
    }

    // -----------------------------------------------------------------
    //  View DTOs
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FileNode(
            String name, String path, String type, Long size, List<FileNode> children) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WriteRequest(String content) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MoveRequest(String from, String to) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkspaceSummary(
            String agentId,
            boolean exists,
            boolean agentsMdExists,
            boolean memoryMdExists,
            int skillCount,
            int subagentCount,
            int dailyMemoryCount) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MemoryView(String memoryMd, List<DailyMemoryFile> dailyFiles) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DailyMemoryFile(String name, long sizeBytes) {}
}
