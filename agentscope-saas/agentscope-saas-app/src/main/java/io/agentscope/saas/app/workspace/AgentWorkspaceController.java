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
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.saas.core.persistence.entity.AgentEntity;
import io.agentscope.saas.core.persistence.repo.AgentRepository;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantResolver;
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
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Workspace file CRUD for the multi-user assistant (Phase B5′). Every operation resolves the
 * caller's per-tenant {@link AbstractFilesystem} via {@link HarnessAgent#workspaceFor(RuntimeContext)}
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
    private static final int MAX_UPLOAD_SIZE = 32 * 1024 * 1024;
    private static final RuntimeContext FS_RC = RuntimeContext.empty();
    private static final String TEXT_PLAIN = "text/plain; charset=utf-8";

    private final HarnessAgent agent;
    private final AgentRepository agentRepository;
    private final TenantResolver tenantResolver;
    private final FileCatalogService fileCatalogService;

    public AgentWorkspaceController(
            HarnessAgent agent,
            AgentRepository agentRepository,
            TenantResolver tenantResolver,
            FileCatalogService fileCatalogService) {
        this.agent = agent;
        this.agentRepository = agentRepository;
        this.tenantResolver = tenantResolver;
        this.fileCatalogService = fileCatalogService;
    }

    // -----------------------------------------------------------------
    //  Summary + memory
    // -----------------------------------------------------------------

    @GetMapping
    public Mono<WorkspaceSummary> summary(
            @PathVariable String agentId, @AuthenticationPrincipal Jwt jwt) {
        RuntimeContext rc = runtimeContext(jwt);
        return Mono.fromCallable(
                        () -> {
                            AbstractFilesystem fs = resolveFilesystem(rc, agentId);
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
        RuntimeContext rc = runtimeContext(jwt);
        return Mono.fromCallable(
                        () -> {
                            AbstractFilesystem fs = resolveFilesystem(rc, agentId);
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
        RuntimeContext rc = runtimeContext(jwt);
        return Mono.fromCallable(
                        () -> {
                            AbstractFilesystem fs = resolveFilesystem(rc, agentId);
                            TenantContext tenant = TenantContext.from(rc);
                            List<FileCatalogService.CatalogFileSummary> catalogFiles =
                                    fileCatalogService.listActiveFiles(tenant);
                            if (recursive) {
                                GlobResult gr = fs.glob(FS_RC, "**/*", "/");
                                if (!gr.isSuccess() || gr.matches() == null) {
                                    return buildTreeFromGlob(
                                            mergeCatalogFileInfos(List.of(), catalogFiles, true));
                                }
                                return buildTreeFromGlob(
                                        mergeCatalogFileInfos(gr.matches(), catalogFiles, true));
                            }
                            LsResult ls = fs.ls(FS_RC, "/");
                            if (!ls.isSuccess() || ls.entries() == null) {
                                return flattenShallow(
                                        mergeCatalogFileInfos(List.of(), catalogFiles, false));
                            }
                            return flattenShallow(
                                    mergeCatalogFileInfos(ls.entries(), catalogFiles, false));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/file")
    public Mono<String> readFile(
            @PathVariable String agentId,
            @RequestParam("path") String path,
            @AuthenticationPrincipal Jwt jwt) {
        RuntimeContext rc = runtimeContext(jwt);
        return Mono.fromCallable(
                        () -> {
                            AbstractFilesystem fs = resolveFilesystem(rc, agentId);
                            TenantContext tenant = TenantContext.from(rc);
                            String abs = toAbsFsPath(path);
                            String rel = toRelFsPath(path);
                            if (!fs.exists(FS_RC, abs)) {
                                return readStoredTextOr404(tenant, rel, path);
                            }
                            ReadResult rr = fs.read(FS_RC, abs, 0, Integer.MAX_VALUE);
                            if (!rr.isSuccess() || rr.fileData() == null) {
                                return readStoredTextOr404(tenant, rel, path);
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

    /**
     * Downloads a workspace file as a binary stream ({@code application/octet-stream} + {@code
     * Content-Disposition: attachment}). Unlike {@link #readFile} (which returns text and replaces
     * binaries with a placeholder), this streams the raw bytes so a file produced by the agent in
     * the sandbox — text or binary — can be saved by the browser. Returns 404 when the file is
     * absent or the filesystem reports a read failure.
     */
    @GetMapping("/file/download")
    public Mono<ResponseEntity<byte[]>> downloadFile(
            @PathVariable String agentId,
            @RequestParam("path") String path,
            @AuthenticationPrincipal Jwt jwt) {
        RuntimeContext rc = runtimeContext(jwt);
        return Mono.fromCallable(
                        () -> {
                            AbstractFilesystem fs = resolveFilesystem(rc, agentId);
                            TenantContext tenant = TenantContext.from(rc);
                            String abs = toAbsFsPath(path);
                            String rel = toRelFsPath(path);
                            List<FileDownloadResponse> results =
                                    fs.downloadFiles(FS_RC, List.of(abs));
                            FileDownloadResponse result =
                                    results == null || results.isEmpty() ? null : results.get(0);
                            if (result == null || !result.isSuccess() || result.content() == null) {
                                return fileCatalogService
                                        .readCurrentFile(tenant, rel)
                                        .map(stored -> downloadResponse(abs, stored.content()))
                                        .orElseThrow(
                                                () ->
                                                        new ResponseStatusException(
                                                                HttpStatus.NOT_FOUND,
                                                                result != null
                                                                                && result.error()
                                                                                        != null
                                                                        ? result.error()
                                                                        : "File not found: "
                                                                                + path));
                            }
                            fileCatalogService.recordWorkspaceFile(
                                    tenant,
                                    agentUuid(agentId),
                                    sessionUuid(rc),
                                    rel,
                                    result.content(),
                                    MediaType.APPLICATION_OCTET_STREAM_VALUE,
                                    FileCatalogService.SOURCE_WORKSPACE_DOWNLOAD,
                                    Map.of("api", "workspace.download"));
                            return downloadResponse(abs, result.content());
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/file")
    public Mono<FileNode> writeFile(
            @PathVariable String agentId,
            @RequestParam("path") String path,
            @RequestBody WriteRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        RuntimeContext rc = runtimeContext(jwt);
        return Mono.fromCallable(
                        () -> {
                            AbstractFilesystem fs = resolveFilesystem(rc, agentId);
                            TenantContext tenant = TenantContext.from(rc);
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
                            fileCatalogService.recordWorkspaceFile(
                                    tenant,
                                    agentUuid(agentId),
                                    sessionUuid(rc),
                                    rel,
                                    bytes,
                                    TEXT_PLAIN,
                                    FileCatalogService.SOURCE_WORKSPACE_WRITE,
                                    Map.of("api", "workspace.write", "existed", existed));
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
        RuntimeContext rc = runtimeContext(jwt);
        return Mono.fromCallable(
                        () -> {
                            if ("dir".equalsIgnoreCase(type)) {
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Empty directory creation is not supported — create a file"
                                                + " inside the directory instead.");
                            }
                            AbstractFilesystem fs = resolveFilesystem(rc, agentId);
                            TenantContext tenant = TenantContext.from(rc);
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
                            fileCatalogService.recordWorkspaceFile(
                                    tenant,
                                    agentUuid(agentId),
                                    sessionUuid(rc),
                                    rel,
                                    new byte[0],
                                    TEXT_PLAIN,
                                    FileCatalogService.SOURCE_WORKSPACE_CREATE,
                                    Map.of("api", "workspace.create"));
                            return new FileNode(basename(rel), rel, "file", 0L, null);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping(value = "/file/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<UploadedFile> uploadFile(
            @PathVariable String agentId,
            @RequestParam(name = "path", required = false) String path,
            @RequestParam(name = "sessionId", required = false) String sessionId,
            @RequestParam(name = "messageId", required = false) String messageId,
            @RequestParam(name = "taskId", required = false) String taskId,
            @RequestPart("file") Mono<FilePart> filePartMono,
            @AuthenticationPrincipal Jwt jwt) {
        RuntimeContext rc = runtimeContext(jwt);
        return filePartMono.flatMap(
                file ->
                        DataBufferUtils.join(file.content())
                                .map(buffer -> bytesFrom(buffer, file.filename()))
                                .flatMap(
                                        bytes ->
                                                Mono.fromCallable(
                                                                () ->
                                                                        uploadFileBytes(
                                                                                agentId, rc, file,
                                                                                path, sessionId,
                                                                                messageId, taskId,
                                                                                bytes))
                                                        .subscribeOn(Schedulers.boundedElastic())));
    }

    @GetMapping("/file/versions")
    public Mono<List<FileCatalogService.FileVersionSummary>> fileVersions(
            @PathVariable String agentId,
            @RequestParam("path") String path,
            @AuthenticationPrincipal Jwt jwt) {
        RuntimeContext rc = runtimeContext(jwt);
        return Mono.fromCallable(
                        () -> {
                            resolveFilesystem(rc, agentId);
                            return fileCatalogService.listVersions(
                                    TenantContext.from(rc), toRelFsPath(path));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/file/version/{versionId}/download")
    public Mono<ResponseEntity<byte[]>> downloadVersion(
            @PathVariable String agentId,
            @PathVariable String versionId,
            @AuthenticationPrincipal Jwt jwt) {
        RuntimeContext rc = runtimeContext(jwt);
        return Mono.fromCallable(
                        () -> {
                            resolveFilesystem(rc, agentId);
                            UUID id = parseUuid(versionId, "versionId");
                            FileCatalogService.StoredFile stored =
                                    fileCatalogService
                                            .readVersion(TenantContext.from(rc), id)
                                            .orElseThrow(
                                                    () ->
                                                            new ResponseStatusException(
                                                                    HttpStatus.NOT_FOUND,
                                                                    "Version not found: "
                                                                            + versionId));
                            return downloadResponse(stored.logicalPath(), stored.content());
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/file/version/{versionId}/restore")
    public Mono<FileNode> restoreVersion(
            @PathVariable String agentId,
            @PathVariable String versionId,
            @RequestParam(name = "path", required = false) String path,
            @AuthenticationPrincipal Jwt jwt) {
        RuntimeContext rc = runtimeContext(jwt);
        return Mono.fromCallable(
                        () -> {
                            AbstractFilesystem fs = resolveFilesystem(rc, agentId);
                            TenantContext tenant = TenantContext.from(rc);
                            UUID id = parseUuid(versionId, "versionId");
                            FileCatalogService.StoredFile stored =
                                    fileCatalogService
                                            .readVersion(tenant, id)
                                            .orElseThrow(
                                                    () ->
                                                            new ResponseStatusException(
                                                                    HttpStatus.NOT_FOUND,
                                                                    "Version not found: "
                                                                            + versionId));
                            String rel =
                                    path != null && !path.isBlank()
                                            ? toRelFsPath(path)
                                            : toRelFsPath(stored.logicalPath());
                            List<FileUploadResponse> ur =
                                    fs.uploadFiles(
                                            FS_RC, List.of(Map.entry(rel, stored.content())));
                            if (ur.isEmpty() || !ur.get(0).isSuccess()) {
                                String err = ur.isEmpty() ? "no response" : ur.get(0).error();
                                throw new ResponseStatusException(
                                        HttpStatus.INTERNAL_SERVER_ERROR,
                                        "Failed to restore file: " + err);
                            }
                            FileCatalogService.FileRecord record =
                                    fileCatalogService
                                            .recordWorkspaceFile(
                                                    tenant,
                                                    agentUuid(agentId),
                                                    sessionUuid(rc),
                                                    rel,
                                                    stored.content(),
                                                    stored.contentType(),
                                                    FileCatalogService.SOURCE_WORKSPACE_RESTORE,
                                                    Map.of("restoredVersionId", versionId))
                                            .orElseThrow(
                                                    () ->
                                                            new ResponseStatusException(
                                                                    HttpStatus
                                                                            .INTERNAL_SERVER_ERROR,
                                                                    "File catalog is not"
                                                                            + " available"));
                            return new FileNode(
                                    basename(record.logicalPath()),
                                    record.logicalPath(),
                                    "file",
                                    record.sizeBytes(),
                                    null);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/file/move")
    public Mono<FileNode> moveNode(
            @PathVariable String agentId,
            @RequestBody MoveRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        RuntimeContext rc = runtimeContext(jwt);
        return Mono.fromCallable(
                        () -> {
                            if (req == null || req.from() == null || req.to() == null) {
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "from and to are required");
                            }
                            AbstractFilesystem fs = resolveFilesystem(rc, agentId);
                            TenantContext tenant = TenantContext.from(rc);
                            String absFrom = toAbsFsPath(req.from());
                            String absTo = toAbsFsPath(req.to());
                            String relFrom = toRelFsPath(req.from());
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
                            fileCatalogService.moveFile(
                                    tenant, agentUuid(agentId), sessionUuid(rc), relFrom, relTo);
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
        RuntimeContext rc = runtimeContext(jwt);
        return Mono.fromRunnable(
                        () -> {
                            AbstractFilesystem fs = resolveFilesystem(rc, agentId);
                            TenantContext tenant = TenantContext.from(rc);
                            String abs = toAbsFsPath(path);
                            String rel = toRelFsPath(path);
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
                            fileCatalogService.markDeleted(tenant, rel);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private String readStoredTextOr404(TenantContext tenant, String relPath, String requestedPath) {
        FileCatalogService.StoredFile stored =
                fileCatalogService
                        .readCurrentFile(tenant, relPath)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "File not found: " + requestedPath));
        byte[] content = stored.content() != null ? stored.content() : new byte[0];
        if (!isProbablyText(stored.contentType(), relPath)) {
            return "(binary file: " + content.length + " bytes; not editable)";
        }
        if (content.length > MAX_FILE_SIZE) {
            return "(file too large to display: " + content.length + " bytes)";
        }
        return stored.text();
    }

    private UploadedFile uploadFileBytes(
            String agentId,
            RuntimeContext rc,
            FilePart file,
            String requestedPath,
            String sessionId,
            String messageId,
            String taskId,
            byte[] bytes) {
        if (bytes.length > MAX_UPLOAD_SIZE) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "Upload exceeds " + MAX_UPLOAD_SIZE + " bytes");
        }
        AbstractFilesystem fs = resolveFilesystem(rc, agentId);
        TenantContext tenant = TenantContext.from(rc);
        String rel = uploadTargetPath(requestedPath, file.filename());
        List<FileUploadResponse> ur = fs.uploadFiles(FS_RC, List.of(Map.entry(rel, bytes)));
        if (ur.isEmpty() || !ur.get(0).isSuccess()) {
            String err = ur.isEmpty() ? "no response" : ur.get(0).error();
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Failed to upload file: " + err);
        }
        UUID agentUuid = agentUuid(agentId);
        UUID sessionUuid = parseOptionalUuid(sessionId);
        UUID messageUuid = parseOptionalUuid(messageId);
        UUID taskUuid = parseOptionalUuid(taskId);
        FileCatalogService.FileRecord record =
                fileCatalogService
                        .recordWorkspaceFile(
                                tenant,
                                agentUuid,
                                sessionUuid,
                                rel,
                                bytes,
                                contentType(file),
                                FileCatalogService.SOURCE_WORKSPACE_UPLOAD,
                                Map.of(
                                        "api",
                                        "workspace.upload",
                                        "filename",
                                        safeFilename(file.filename())))
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.INTERNAL_SERVER_ERROR,
                                                "File catalog is not available"));
        FileCatalogService.AttachmentRecord attachment =
                fileCatalogService
                        .attachFile(
                                tenant,
                                agentUuid,
                                sessionUuid,
                                messageUuid,
                                taskUuid,
                                record,
                                "workspace_upload",
                                Map.of("filename", safeFilename(file.filename())))
                        .orElse(null);
        return new UploadedFile(
                record.fileId().toString(),
                record.versionId().toString(),
                attachment != null ? attachment.id().toString() : null,
                record.logicalPath(),
                record.sizeBytes(),
                record.sha256());
    }

    private static byte[] bytesFrom(DataBuffer buffer, String filename) {
        try {
            int readable = buffer.readableByteCount();
            if (readable > MAX_UPLOAD_SIZE) {
                throw new ResponseStatusException(
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        "Upload exceeds " + MAX_UPLOAD_SIZE + " bytes: " + filename);
            }
            byte[] bytes = new byte[readable];
            buffer.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private static String uploadTargetPath(String requestedPath, String filename) {
        String safeName = safeFilename(filename);
        if (requestedPath == null || requestedPath.isBlank()) {
            return toRelFsPath(safeName);
        }
        String trimmed = requestedPath.trim();
        if (trimmed.endsWith("/")) {
            return toRelFsPath(trimmed + safeName);
        }
        return toRelFsPath(trimmed);
    }

    private static String safeFilename(String filename) {
        String value = filename == null ? "" : filename.replace('\\', '/').trim();
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        value = value.replace('\0', '_').trim();
        if (value.isBlank() || ".".equals(value) || "..".equals(value)) {
            return "upload.bin";
        }
        return value;
    }

    private static String contentType(FilePart file) {
        MediaType contentType = file.headers().getContentType();
        return contentType != null
                ? contentType.toString()
                : MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    private static ResponseEntity<byte[]> downloadResponse(String absPath, byte[] content) {
        byte[] bytes = content != null ? content : new byte[0];
        String leaf = absPath;
        int slash = absPath.lastIndexOf('/');
        if (slash >= 0 && slash < absPath.length() - 1) {
            leaf = absPath.substring(slash + 1);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("attachment", leaf);
        headers.setContentLength(bytes.length);
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    // -----------------------------------------------------------------
    //  Tenant resolution
    // -----------------------------------------------------------------

    /**
     * Validates that {@code agentId} belongs to the caller's org, then returns the caller's per-user
     * filesystem. 404 (not 403) when the agent is absent so existence is not leaked.
     */
    private AbstractFilesystem resolveFilesystem(RuntimeContext rc, String agentId) {
        TenantContext tenant = TenantContext.from(rc);
        UUID orgId = orgId(tenant);
        UUID userId = userId(tenant);
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
        return agent.workspaceFor(rc).getFilesystem();
    }

    private RuntimeContext runtimeContext(Jwt jwt) {
        TenantContext tenant = tenantResolver.resolve(jwt != null ? jwt.getClaims() : Map.of());
        return RuntimeContext.builder()
                .userId(tenant.userId())
                .put(TenantContext.class, tenant)
                .put(TenantContext.ATTR_KEY, tenant)
                .build();
    }

    private static UUID orgId(TenantContext tenant) {
        return uuidClaim("org_id", tenant != null ? tenant.orgId() : null);
    }

    private static UUID userId(TenantContext tenant) {
        return uuidClaim("user_id", tenant != null ? tenant.userId() : null);
    }

    private static UUID uuidClaim(String name, String value) {
        try {
            return UUID.fromString(value);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid tenant " + name);
        }
    }

    private static UUID agentUuid(String agentId) {
        try {
            return UUID.fromString(agentId);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: " + agentId);
        }
    }

    private static UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid " + field);
        }
    }

    private static UUID parseOptionalUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid UUID: " + value);
        }
    }

    private static UUID sessionUuid(RuntimeContext rc) {
        String sessionId = rc != null ? rc.getSessionId() : null;
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(sessionId);
        } catch (IllegalArgumentException e) {
            return null;
        }
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

    private static boolean isProbablyText(String contentType, String path) {
        String ct = contentType != null ? contentType.toLowerCase() : "";
        if (ct.startsWith("text/")
                || ct.contains("json")
                || ct.contains("xml")
                || ct.contains("yaml")
                || ct.contains("javascript")
                || ct.contains("typescript")) {
            return true;
        }
        String p = path != null ? path.toLowerCase() : "";
        return p.endsWith(".txt")
                || p.endsWith(".md")
                || p.endsWith(".json")
                || p.endsWith(".yaml")
                || p.endsWith(".yml")
                || p.endsWith(".xml")
                || p.endsWith(".csv")
                || p.endsWith(".log")
                || p.endsWith(".java")
                || p.endsWith(".js")
                || p.endsWith(".ts")
                || p.endsWith(".py")
                || p.endsWith(".sh")
                || p.endsWith(".sql");
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

    private static List<FileInfo> mergeCatalogFileInfos(
            List<FileInfo> workspaceFiles,
            List<FileCatalogService.CatalogFileSummary> catalogFiles,
            boolean recursive) {
        Map<String, FileInfo> seen = new LinkedHashMap<>();
        if (workspaceFiles != null) {
            for (FileInfo fi : workspaceFiles) {
                String rel = relPath(fi.path());
                if (!rel.isEmpty()) {
                    seen.putIfAbsent(rel, fi);
                }
            }
        }
        if (catalogFiles != null) {
            for (FileCatalogService.CatalogFileSummary catalogFile : catalogFiles) {
                String rel = relPath(catalogFile.logicalPath());
                if (rel.isEmpty()) {
                    continue;
                }
                if (recursive) {
                    seen.putIfAbsent(
                            rel, FileInfo.ofFile("/" + rel, catalogFile.sizeBytes(), null));
                    continue;
                }
                int slash = rel.indexOf('/');
                if (slash > 0) {
                    String rootDir = rel.substring(0, slash);
                    seen.putIfAbsent(rootDir, FileInfo.ofDir("/" + rootDir, null));
                } else {
                    seen.putIfAbsent(
                            rel, FileInfo.ofFile("/" + rel, catalogFile.sizeBytes(), null));
                }
            }
        }
        return new ArrayList<>(seen.values());
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
    public record UploadedFile(
            String fileId,
            String versionId,
            String attachmentId,
            String path,
            long sizeBytes,
            String sha256) {}

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
