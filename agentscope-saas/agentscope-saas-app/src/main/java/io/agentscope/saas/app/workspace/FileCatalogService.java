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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.model.FileAttachmentEntity;
import io.agentscope.saas.domain.model.FileEntity;
import io.agentscope.saas.domain.model.FileVersionEntity;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.SessionFence;
import io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException;
import io.agentscope.saas.domain.repository.FileAttachmentRepository;
import io.agentscope.saas.domain.repository.FileRepository;
import io.agentscope.saas.domain.repository.FileVersionRepository;
import io.agentscope.saas.domain.repository.OrgRepository;
import io.agentscope.saas.domain.repository.UserRepository;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Execution;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Intent;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Publication;
import io.agentscope.saas.storage.FileObject;
import io.agentscope.saas.storage.FileObjectStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Durable file catalog for assistant workspaces.
 *
 * <p>PostgreSQL owns metadata, versioning and authorization. File bytes are immutable objects in the
 * configured {@link FileObjectStore}: MinIO/S3 in production, PG BYTEA fallback in local/test.
 */
@Service
public class FileCatalogService {
    private static final Logger log = LoggerFactory.getLogger(FileCatalogService.class);

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DELETED = "deleted";
    public static final String SOURCE_WORKSPACE_WRITE = "workspace_write";
    public static final String SOURCE_WORKSPACE_CREATE = "workspace_create";
    public static final String SOURCE_WORKSPACE_DOWNLOAD = "workspace_download_capture";
    public static final String SOURCE_WORKSPACE_UPLOAD = "workspace_upload";
    public static final String SOURCE_WORKSPACE_MOVE = "workspace_move";
    public static final String SOURCE_WORKSPACE_DELETE = "workspace_delete";
    public static final String SOURCE_WORKSPACE_RESTORE = "workspace_restore";
    public static final String SOURCE_SANDBOX_PROJECTION = "sandbox_projection";

    private final FileRepository fileRepository;
    private final FileVersionRepository fileVersionRepository;
    private final FileAttachmentRepository fileAttachmentRepository;
    private final OrgRepository orgRepository;
    private final UserRepository userRepository;
    private final ObjectProvider<FileObjectStore> objectStoreProvider;
    private final ObjectMapper objectMapper;
    private final SaasProperties properties;
    private final RunOrchestrationRepository runs;
    private final FilePublicationCoordinator publications;

    public FileCatalogService(
            FileRepository fileRepository,
            FileVersionRepository fileVersionRepository,
            FileAttachmentRepository fileAttachmentRepository,
            OrgRepository orgRepository,
            UserRepository userRepository,
            ObjectProvider<FileObjectStore> objectStoreProvider,
            ObjectMapper objectMapper,
            SaasProperties properties) {
        this(
                fileRepository,
                fileVersionRepository,
                fileAttachmentRepository,
                orgRepository,
                userRepository,
                objectStoreProvider,
                objectMapper,
                properties,
                null);
    }

    public FileCatalogService(
            FileRepository fileRepository,
            FileVersionRepository fileVersionRepository,
            FileAttachmentRepository fileAttachmentRepository,
            OrgRepository orgRepository,
            UserRepository userRepository,
            ObjectProvider<FileObjectStore> objectStoreProvider,
            ObjectMapper objectMapper,
            SaasProperties properties,
            RunOrchestrationRepository runs) {
        this(
                fileRepository,
                fileVersionRepository,
                fileAttachmentRepository,
                orgRepository,
                userRepository,
                objectStoreProvider,
                objectMapper,
                properties,
                runs,
                null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FileCatalogService(
            FileRepository fileRepository,
            FileVersionRepository fileVersionRepository,
            FileAttachmentRepository fileAttachmentRepository,
            OrgRepository orgRepository,
            UserRepository userRepository,
            ObjectProvider<FileObjectStore> objectStoreProvider,
            ObjectMapper objectMapper,
            SaasProperties properties,
            RunOrchestrationRepository runs,
            FilePublicationCoordinator publications) {
        this.fileRepository = fileRepository;
        this.fileVersionRepository = fileVersionRepository;
        this.fileAttachmentRepository = fileAttachmentRepository;
        this.orgRepository = orgRepository;
        this.userRepository = userRepository;
        this.objectStoreProvider = objectStoreProvider;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.runs = runs;
        this.publications = publications;
    }

    @Transactional(propagation = Propagation.NEVER)
    public Optional<FileRecord> recordWorkspaceFile(
            TenantContext tenant,
            UUID agentId,
            UUID sessionId,
            String logicalPath,
            byte[] content,
            String contentType,
            String source,
            Map<String, Object> metadata) {
        return recordWorkspaceFileLocked(
                tenant,
                agentId,
                sessionId,
                logicalPath,
                content,
                contentType,
                source,
                metadata,
                null,
                null,
                null,
                null);
    }

    @Transactional(propagation = Propagation.NEVER)
    public Optional<FileRecord> recordWorkspaceFileForExecution(
            TenantContext tenant,
            UUID agentId,
            UUID runId,
            SessionFence fence,
            String logicalPath,
            byte[] content,
            String contentType,
            String source,
            Map<String, Object> metadata) {
        Objects.requireNonNull(fence, "sessionFence");
        return recordWorkspaceFileLocked(
                tenant,
                agentId,
                fence.sessionId(),
                logicalPath,
                content,
                contentType,
                source,
                metadata,
                null,
                runId,
                fence,
                null);
    }

    /** Runs the workspace mutation after durable quota admission, outside SQL transactions. */
    @Transactional(propagation = Propagation.NEVER)
    public Optional<FileRecord> recordWorkspaceFileWithWrite(
            TenantContext tenant,
            UUID agentId,
            UUID sessionId,
            String logicalPath,
            byte[] content,
            String contentType,
            String source,
            Map<String, Object> metadata,
            Runnable workspaceWrite) {
        return recordWorkspaceFileLocked(
                tenant,
                agentId,
                sessionId,
                logicalPath,
                content,
                contentType,
                source,
                metadata,
                Objects.requireNonNull(workspaceWrite, "workspaceWrite"),
                null,
                null,
                null);
    }

    private Optional<FileRecord> recordWorkspaceFileLocked(
            TenantContext tenant,
            UUID agentId,
            UUID sessionId,
            String logicalPath,
            byte[] content,
            String contentType,
            String source,
            Map<String, Object> metadata,
            Runnable workspaceWrite,
            UUID runId,
            SessionFence fence,
            Publication prepared) {
        return recordWorkspaceFileLocked(
                tenant,
                agentId,
                sessionId,
                logicalPath,
                content,
                contentType,
                source,
                metadata,
                workspaceWrite,
                runId,
                fence,
                prepared,
                null);
    }

    @Transactional(propagation = Propagation.NEVER)
    public Optional<FileRecord> recordWorkspaceFileForAttempt(
            TenantContext tenant,
            UUID agent,
            UUID run,
            SessionFence fence,
            Execution execution,
            String path,
            byte[] bytes,
            String contentType,
            String source,
            Map<String, Object> metadata) {
        Objects.requireNonNull(run);
        Objects.requireNonNull(execution);
        return recordWorkspaceFileLocked(
                tenant,
                agent,
                fence.sessionId(),
                path,
                bytes,
                contentType,
                source,
                metadata,
                null,
                run,
                fence,
                null,
                execution);
    }

    private Optional<FileRecord> recordWorkspaceFileLocked(
            TenantContext tenant,
            UUID agentId,
            UUID sessionId,
            String logicalPath,
            byte[] content,
            String contentType,
            String source,
            Map<String, Object> metadata,
            Runnable workspaceWrite,
            UUID runId,
            SessionFence fence,
            Publication prepared,
            Execution execution) {
        if (!properties.getFileStore().isEnabled()) {
            runWorkspaceWrite(workspaceWrite);
            return Optional.empty();
        }
        Optional<UUID> orgId = parseUuid(tenant != null ? tenant.orgId() : null);
        Optional<UUID> userId = parseUuid(tenant != null ? tenant.userId() : null);
        FileObjectStore store = objectStoreProvider.getIfAvailable();
        if (orgId.isEmpty() || userId.isEmpty() || store == null) {
            runWorkspaceWrite(workspaceWrite);
            return Optional.empty();
        }
        String path = normalizePath(logicalPath);
        byte[] bytes = content != null ? content : new byte[0];
        long maxFileBytes = properties.getFileStore().getMaxFileBytes();
        if (maxFileBytes > 0 && bytes.length > maxFileBytes) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "File exceeds configured limit of " + maxFileBytes + " bytes");
        }
        String sha256 = sha256(bytes);

        if (publications != null && prepared == null) {
            return withTenantOrg(
                    orgId.get().toString(),
                    () ->
                            Optional.of(
                                    publications.publish(
                                            () ->
                                                    preparePublication(
                                                            tenant,
                                                            orgId.get(),
                                                            userId.get(),
                                                            agentId,
                                                            sessionId,
                                                            runId,
                                                            fence,
                                                            path,
                                                            bytes.length,
                                                            sha256,
                                                            store),
                                            p ->
                                                    prepareIntent(
                                                            p,
                                                            contentType,
                                                            source,
                                                            metadata,
                                                            execution,
                                                            workspaceWrite != null),
                                            p -> {
                                                if (p.ownsObject()) {
                                                    putObject(
                                                            store,
                                                            p.orgId(),
                                                            p.objectKey(),
                                                            bytes,
                                                            contentType,
                                                            sha256);
                                                    byte[] persisted =
                                                            getObject(
                                                                    store,
                                                                    p.orgId(),
                                                                    p.objectKey());
                                                    if (persisted == null
                                                            || persisted.length != bytes.length
                                                            || !sha256.equals(sha256(persisted)))
                                                        throw new IllegalStateException(
                                                                "FILE_PUBLICATION_INTEGRITY_FAILED");
                                                }
                                                runWorkspaceWrite(workspaceWrite);
                                            },
                                            p ->
                                                    recordWorkspaceFileLocked(
                                                                    tenant,
                                                                    agentId,
                                                                    sessionId,
                                                                    path,
                                                                    bytes,
                                                                    contentType,
                                                                    source,
                                                                    metadata,
                                                                    null,
                                                                    runId,
                                                                    fence,
                                                                    p,
                                                                    execution)
                                                            .orElseThrow())));
        }

        return withTenantOrg(
                orgId.get().toString(),
                () -> {
                    orgRepository
                            .lockTenantOrg(orgId.get())
                            .orElseThrow(() -> new IllegalStateException("Organization not found"));
                    userRepository
                            .lockTenantUser(orgId.get(), userId.get())
                            .orElseThrow(() -> new IllegalStateException("User not found"));
                    validateExecutionFence(tenant, agentId, runId, fence);
                    if (prepared != null)
                        lockPublicationSession(tenant, agentId, sessionId, runId, fence);
                    Intent intent = prepared == null ? null : publications.intent(prepared);
                    if (intent != null) publications.requireExecution(prepared, intent);
                    Optional<FileEntity> existing =
                            fence == null
                                    ? fileRepository.lockByOrgUserPath(
                                            orgId.get(), userId.get(), path)
                                    : fileRepository.findByOrgIdAndUserIdAndLogicalPath(
                                            orgId.get(), userId.get(), path);
                    FileEntity file =
                            existing.orElseGet(
                                    () ->
                                            newFile(
                                                    orgId.get(),
                                                    userId.get(),
                                                    agentId,
                                                    sessionId,
                                                    path,
                                                    source));
                    Optional<FileVersionEntity> current =
                            existing.isEmpty()
                                    ? Optional.empty()
                                    : currentVersion(file, orgId.get());
                    if (intent != null) requireBase(intent, existing);
                    long replacedBytes = replacementSize(file, current);
                    enforceQuota(
                            orgId.get(),
                            userId.get(),
                            replacedBytes,
                            bytes.length,
                            prepared == null ? null : prepared.id());
                    if (workspaceWrite != null) {
                        workspaceWrite.run();
                    }
                    String objectKey =
                            prepared == null
                                    ? objectKey(orgId.get(), userId.get(), sha256)
                                    : prepared.objectKey();
                    String objectBackend = prepared == null ? store.backend() : prepared.backend();
                    if (fence != null || prepared != null) {
                        if (prepared == null) {
                            if (current.isPresent() && sha256.equals(current.get().getSha256())) {
                                objectKey = current.get().getObjectKey();
                                objectBackend = current.get().getStorageBackend();
                            } else
                                putObject(
                                        store, orgId.get(), objectKey, bytes, contentType, sha256);
                        }
                        // Session before file avoids a cycle with chat attachment FK locks.
                        lockExecutionFence(tenant, agentId, runId, fence);
                        existing =
                                fileRepository.lockByOrgUserPath(orgId.get(), userId.get(), path);
                        file =
                                existing.orElseGet(
                                        () ->
                                                newFile(
                                                        orgId.get(),
                                                        userId.get(),
                                                        agentId,
                                                        sessionId,
                                                        path,
                                                        source));
                        current =
                                existing.isEmpty()
                                        ? Optional.empty()
                                        : currentVersion(file, orgId.get());
                        enforceQuota(
                                orgId.get(),
                                userId.get(),
                                replacementSize(file, current),
                                bytes.length,
                                prepared == null ? null : prepared.id());
                    }
                    if (prepared != null
                            && !prepared.ownsObject()
                            && (current.isEmpty()
                                    || !STATUS_ACTIVE.equals(file.getStatus())
                                    || !sha256.equals(current.get().getSha256())
                                    || !objectKey.equals(current.get().getObjectKey())))
                        throw new IllegalStateException("FILE_PUBLICATION_SOURCE_CHANGED");
                    if (current.isPresent()
                            && sha256.equals(current.get().getSha256())
                            && (prepared == null
                                    || objectKey.equals(current.get().getObjectKey()))) {
                        FileVersionEntity version = current.get();
                        file.setAgentId(agentId);
                        file.setSessionId(sessionId);
                        file.setCurrentVersionId(version.getId());
                        file.setSource(source);
                        file.setStatus(STATUS_ACTIVE);
                        file.setUpdatedAt(OffsetDateTime.now());
                        fileRepository.save(file);
                        return Optional.of(
                                new FileRecord(
                                        file.getId(),
                                        version.getId(),
                                        path,
                                        version.getVersionNo(),
                                        version.getObjectKey(),
                                        version.getStorageBackend(),
                                        version.getSizeBytes() != null
                                                ? version.getSizeBytes()
                                                : 0L,
                                        version.getSha256()));
                    }
                    if (fence == null && prepared == null)
                        putObject(store, orgId.get(), objectKey, bytes, contentType, sha256);
                    if (existing.isEmpty()) fileRepository.saveAndFlush(file);
                    long versionNo =
                            existing.isEmpty()
                                    ? 1L
                                    : fileVersionRepository.maxVersionNo(file.getId()) + 1L;
                    FileVersionEntity version =
                            newVersion(
                                    file,
                                    agentId,
                                    sessionId,
                                    versionNo,
                                    objectKey,
                                    objectBackend,
                                    contentType,
                                    bytes.length,
                                    sha256,
                                    source,
                                    metadata);
                    fileVersionRepository.save(version);
                    file.setAgentId(agentId);
                    file.setSessionId(sessionId);
                    file.setCurrentVersionId(version.getId());
                    file.setSource(source);
                    file.setStatus(STATUS_ACTIVE);
                    file.setUpdatedAt(OffsetDateTime.now());
                    fileRepository.save(file);
                    return Optional.of(
                            new FileRecord(
                                    file.getId(),
                                    version.getId(),
                                    path,
                                    versionNo,
                                    objectKey,
                                    objectBackend,
                                    bytes.length,
                                    sha256));
                });
    }

    private Publication preparePublication(
            TenantContext tenant,
            UUID org,
            UUID user,
            UUID agent,
            UUID session,
            UUID run,
            SessionFence fence,
            String path,
            long size,
            String digest,
            FileObjectStore store) {
        lockQuotaOwners(org, user);
        publications.requireIdlePath(org, user, path);
        lockPublicationSession(tenant, agent, session, run, fence);
        Optional<FileEntity> file =
                fileRepository.findByOrgIdAndUserIdAndLogicalPath(org, user, path);
        Optional<FileVersionEntity> current = file.flatMap(f -> currentVersion(f, org));
        long replaced = file.map(f -> replacementSize(f, current)).orElse(0L);
        enforceQuota(org, user, replaced, size);
        boolean reuse =
                file.isPresent()
                        && STATUS_ACTIVE.equals(file.get().getStatus())
                        && current.isPresent()
                        && digest.equals(current.get().getSha256())
                        && store.backend().equals(current.get().getStorageBackend());
        UUID id = UUID.randomUUID();
        String prefix = properties.getFileStore().getObjectKeyPrefix();
        if (prefix == null || prefix.isBlank()) prefix = "files/";
        if (!prefix.endsWith("/")) prefix += "/";
        String key =
                reuse
                        ? current.get().getObjectKey()
                        : prefix
                                + "org="
                                + org
                                + "/user="
                                + user
                                + "/publications/"
                                + id
                                + "-"
                                + digest.substring(0, 16);
        OffsetDateTime until = publications.leaseUntil();
        return new Publication(
                id,
                org,
                user,
                agent,
                session,
                run,
                fence == null ? null : fence.generation(),
                path,
                key,
                store.backend(),
                digest,
                size,
                Math.max(0L, size - replaced),
                !reuse,
                "STAGED",
                until,
                until,
                null,
                0,
                null);
    }

    private Intent prepareIntent(
            Publication p,
            String contentType,
            String source,
            Map<String, Object> metadata,
            Execution execution,
            boolean rawWriteRequired) {
        Optional<FileEntity> base =
                fileRepository.findByOrgIdAndUserIdAndLogicalPath(
                        p.orgId(), p.userId(), p.logicalPath());
        String json;
        try {
            json = objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Invalid publication metadata");
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > 32768)
            throw new IllegalArgumentException("Publication metadata exceeds limit");
        Intent i =
                new Intent(
                        p.id(),
                        base.map(FileEntity::getId).orElse(null),
                        base.map(FileEntity::getCurrentVersionId).orElse(null),
                        base.map(FileEntity::getStatus).orElse(null),
                        contentType,
                        source,
                        json,
                        execution == null ? null : execution.taskId(),
                        execution == null ? null : execution.agentRunId(),
                        execution == null ? null : execution.attemptId(),
                        execution == null ? null : execution.leaseOwner(),
                        rawWriteRequired);
        publications.requireExecution(p, i);
        return i;
    }

    private static void requireBase(Intent i, Optional<FileEntity> file) {
        if (!Objects.equals(i.baseFileId(), file.map(FileEntity::getId).orElse(null))
                || !Objects.equals(
                        i.baseVersionId(), file.map(FileEntity::getCurrentVersionId).orElse(null))
                || !Objects.equals(i.baseStatus(), file.map(FileEntity::getStatus).orElse(null)))
            throw new IllegalStateException("FILE_PUBLICATION_BASE_CHANGED");
    }

    @Transactional(propagation = Propagation.NEVER)
    public boolean recoverPublication(Publication p, UUID token) {
        return withTenantOrg(
                p.orgId().toString(),
                () -> {
                    var tenant =
                            new TenantContext(
                                    p.orgId().toString(),
                                    p.userId().toString(),
                                    "member",
                                    "standard",
                                    0,
                                    0);
                    Intent intent =
                            Objects.requireNonNull(
                                    publications.intent(p), "Publication intent missing");
                    long readLimit = properties.getFileStore().getMaxFileBytes();
                    if (p.sizeBytes() < 0 || (readLimit > 0 && p.sizeBytes() > readLimit))
                        throw new IllegalStateException("FILE_PUBLICATION_READ_LIMIT");
                    SessionFence fence =
                            p.sessionGeneration() == null
                                    ? null
                                    : new SessionFence(p.sessionId(), p.sessionGeneration());
                    boolean claimed =
                            publications.claimRecovery(
                                    p,
                                    token,
                                    () -> {
                                        lockQuotaOwners(p.orgId(), p.userId());
                                        lockPublicationSession(
                                                tenant,
                                                p.agentId(),
                                                p.sessionId(),
                                                p.runId(),
                                                fence);
                                        publications.requireExecution(p, intent);
                                        publications.requireIdlePath(
                                                p.orgId(), p.userId(), p.logicalPath());
                                        var file =
                                                fileRepository.lockByOrgUserPath(
                                                        p.orgId(), p.userId(), p.logicalPath());
                                        requireBase(intent, file);
                                        var version =
                                                file.flatMap(f -> currentVersion(f, p.orgId()));
                                        enforceQuota(
                                                p.orgId(),
                                                p.userId(),
                                                file.map(f -> replacementSize(f, version))
                                                        .orElse(0L),
                                                p.sizeBytes(),
                                                p.id());
                                    });
                    if (!claimed) return false;
                    FileObjectStore store = objectStoreProvider.getIfAvailable();
                    if (store == null || !p.backend().equals(store.backend()))
                        throw new IllegalStateException("FILE_PUBLICATION_BACKEND_CHANGED");
                    byte[] bytes;
                    try {
                        bytes = store.getBounded(p.orgId(), p.objectKey(), p.sizeBytes());
                    } catch (Exception error) {
                        throw new FilePublicationRetryableException();
                    }
                    if (bytes == null
                            || bytes.length != p.sizeBytes()
                            || !p.sha256().equals(sha256(bytes)))
                        throw new IllegalStateException("FILE_PUBLICATION_INTEGRITY_FAILED");
                    Map<String, Object> metadata;
                    try {
                        if (intent.metadataJson().getBytes(StandardCharsets.UTF_8).length > 65536)
                            throw new IllegalArgumentException();
                        var tree = objectMapper.readTree(intent.metadataJson());
                        if (!tree.isObject() || objectMapper.writeValueAsBytes(tree).length > 32768)
                            throw new IllegalArgumentException();
                        metadata =
                                objectMapper.convertValue(
                                        tree,
                                        new com.fasterxml.jackson.core.type.TypeReference<
                                                Map<String, Object>>() {});
                    } catch (Exception malformed) {
                        throw new IllegalStateException("FILE_PUBLICATION_METADATA_INVALID");
                    }
                    publications.commitRecovery(
                            p,
                            token,
                            candidate ->
                                    recordWorkspaceFileLocked(
                                                    tenant,
                                                    p.agentId(),
                                                    p.sessionId(),
                                                    p.logicalPath(),
                                                    bytes,
                                                    intent.contentType(),
                                                    intent.source(),
                                                    metadata,
                                                    null,
                                                    p.runId(),
                                                    fence,
                                                    candidate,
                                                    intent.execution())
                                            .orElseThrow());
                    return true;
                });
    }

    private void lockQuotaOwners(UUID org, UUID user) {
        orgRepository
                .lockTenantOrg(org)
                .orElseThrow(() -> new IllegalStateException("Organization not found"));
        userRepository
                .lockTenantUser(org, user)
                .orElseThrow(() -> new IllegalStateException("User not found"));
    }

    private void lockPublicationSession(
            TenantContext tenant, UUID agent, UUID session, UUID run, SessionFence fence) {
        if (fence != null) {
            lockExecutionFence(tenant, agent, run, fence);
        } else if (session != null) {
            if (runs == null) throw new SessionExecutionRevokedException();
            runs.lockSessionGeneration(
                            session,
                            UUID.fromString(tenant.orgId()),
                            UUID.fromString(tenant.userId()),
                            agent)
                    .orElseThrow(SessionExecutionRevokedException::new);
        }
    }

    private static long replacementSize(FileEntity file, Optional<FileVersionEntity> current) {
        return STATUS_ACTIVE.equals(file.getStatus())
                        && current.isPresent()
                        && current.get().getSizeBytes() != null
                ? current.get().getSizeBytes()
                : 0L;
    }

    private void validateExecutionFence(
            TenantContext tenant, UUID agentId, UUID runId, SessionFence fence) {
        if (fence == null) return;
        if (runs == null) throw new SessionExecutionRevokedException();
        UUID org = UUID.fromString(tenant.orgId()), user = UUID.fromString(tenant.userId());
        var current =
                runId == null
                        ? runs.findSessionFence(fence.sessionId(), org, user, agentId)
                        : runs.findCurrentSessionFence(runId, org, user, agentId);
        if (current.filter(fence::equals).isEmpty()) throw new SessionExecutionRevokedException();
    }

    private void lockExecutionFence(
            TenantContext tenant, UUID agentId, UUID runId, SessionFence fence) {
        if (fence == null) return;
        if (runs == null) throw new SessionExecutionRevokedException();
        UUID org = UUID.fromString(tenant.orgId()), user = UUID.fromString(tenant.userId());
        if (runId == null) {
            long generation =
                    runs.lockSessionGeneration(fence.sessionId(), org, user, agentId)
                            .orElseThrow(SessionExecutionRevokedException::new);
            if (generation != fence.generation()) throw new SessionExecutionRevokedException();
        } else {
            var current =
                    runs.lockCurrentSessionFence(runId, org, user, agentId)
                            .orElseThrow(SessionExecutionRevokedException::new);
            if (!fence.equals(current)) throw new SessionExecutionRevokedException();
        }
    }

    private void enforceQuota(UUID orgId, UUID userId, long replacedBytes, long incomingBytes) {
        enforceQuota(orgId, userId, replacedBytes, incomingBytes, null);
    }

    private void enforceQuota(
            UUID orgId, UUID userId, long replacedBytes, long incomingBytes, UUID publicationId) {
        SaasProperties.FileStore cfg = properties.getFileStore();
        long userLimit = cfg.getMaxUserBytes();
        long userUsage = fileVersionRepository.currentUsageByUser(orgId, userId);
        if (publications != null)
            userUsage =
                    projectedUsage(
                            userUsage, 0, publications.reserved(orgId, userId, publicationId));
        long projectedUser = projectedUsage(userUsage, replacedBytes, incomingBytes);
        if (userLimit > 0 && projectedUser > userLimit) {
            throw new ResponseStatusException(
                    HttpStatus.INSUFFICIENT_STORAGE,
                    "User file quota exceeded: " + projectedUser + " > " + userLimit);
        }
        long orgLimit = cfg.getMaxOrgBytes();
        long orgUsage = fileVersionRepository.currentUsageByOrg(orgId);
        if (publications != null)
            orgUsage =
                    projectedUsage(orgUsage, 0, publications.reserved(orgId, null, publicationId));
        long projectedOrg = projectedUsage(orgUsage, replacedBytes, incomingBytes);
        if (orgLimit > 0 && projectedOrg > orgLimit) {
            throw new ResponseStatusException(
                    HttpStatus.INSUFFICIENT_STORAGE,
                    "Organization file quota exceeded: " + projectedOrg + " > " + orgLimit);
        }
    }

    private static long projectedUsage(long current, long replaced, long incoming) {
        long base = Math.max(0L, current - Math.max(0L, replaced));
        try {
            return Math.addExact(base, Math.max(0L, incoming));
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private static void runWorkspaceWrite(Runnable workspaceWrite) {
        if (workspaceWrite != null) {
            workspaceWrite.run();
        }
    }

    @Transactional(propagation = Propagation.NEVER)
    public Optional<StoredFile> readCurrentFile(TenantContext tenant, String logicalPath) {
        if (!properties.getFileStore().isEnabled()) {
            return Optional.empty();
        }
        Optional<UUID> orgId = parseUuid(tenant != null ? tenant.orgId() : null);
        Optional<UUID> userId = parseUuid(tenant != null ? tenant.userId() : null);
        FileObjectStore store = objectStoreProvider.getIfAvailable();
        if (orgId.isEmpty() || userId.isEmpty() || store == null) {
            return Optional.empty();
        }
        String path = normalizePath(logicalPath);
        return withTenantOrg(
                orgId.get().toString(),
                () ->
                        fileRepository
                                .findByOrgIdAndUserIdAndLogicalPath(orgId.get(), userId.get(), path)
                                .filter(f -> STATUS_ACTIVE.equals(f.getStatus()))
                                .flatMap(
                                        f ->
                                                f.getCurrentVersionId() == null
                                                        ? Optional.empty()
                                                        : fileVersionRepository.findByIdAndOrgId(
                                                                f.getCurrentVersionId(),
                                                                orgId.get()))
                                .map(
                                        version ->
                                                new StoredFile(
                                                        path,
                                                        version.getContentType(),
                                                        getObject(
                                                                store,
                                                                orgId.get(),
                                                                version.getObjectKey()),
                                                        version.getSizeBytes(),
                                                        version.getSha256())));
    }

    public FileQuotaUsage currentUsage(TenantContext tenant) {
        Optional<UUID> orgId = parseUuid(tenant != null ? tenant.orgId() : null);
        Optional<UUID> userId = parseUuid(tenant != null ? tenant.userId() : null);
        SaasProperties.FileStore cfg = properties.getFileStore();
        if (!cfg.isEnabled() || orgId.isEmpty() || userId.isEmpty()) {
            return new FileQuotaUsage(
                    0L, cfg.getMaxUserBytes(), 0L, cfg.getMaxOrgBytes(), cfg.getMaxFileBytes());
        }
        return withTenantOrg(
                orgId.get().toString(),
                () ->
                        new FileQuotaUsage(
                                fileVersionRepository.currentUsageByUser(orgId.get(), userId.get()),
                                cfg.getMaxUserBytes(),
                                fileVersionRepository.currentUsageByOrg(orgId.get()),
                                cfg.getMaxOrgBytes(),
                                cfg.getMaxFileBytes()));
    }

    @Transactional(readOnly = true)
    public List<CatalogFileSummary> listActiveFiles(TenantContext tenant) {
        if (!properties.getFileStore().isEnabled()) {
            return List.of();
        }
        Optional<UUID> orgId = parseUuid(tenant != null ? tenant.orgId() : null);
        Optional<UUID> userId = parseUuid(tenant != null ? tenant.userId() : null);
        if (orgId.isEmpty() || userId.isEmpty()) {
            return List.of();
        }
        return withTenantOrg(
                orgId.get().toString(),
                () -> {
                    List<FileEntity> files =
                            fileRepository.findByOrgIdAndUserIdAndStatusOrderByLogicalPathAsc(
                                    orgId.get(), userId.get(), STATUS_ACTIVE);
                    Map<UUID, FileVersionEntity> versions =
                            fileVersionRepository.findAllById(currentVersionIds(files)).stream()
                                    .collect(Collectors.toMap(FileVersionEntity::getId, v -> v));
                    return files.stream()
                            .map(
                                    file -> {
                                        FileVersionEntity version =
                                                versions.get(file.getCurrentVersionId());
                                        return new CatalogFileSummary(
                                                file.getLogicalPath(),
                                                version != null && version.getSizeBytes() != null
                                                        ? version.getSizeBytes()
                                                        : 0L);
                                    })
                            .toList();
                });
    }

    @Transactional(readOnly = true)
    public Map<String, UUID> activeFileVersions(TenantContext tenant) {
        Optional<UUID> orgId = parseUuid(tenant != null ? tenant.orgId() : null);
        Optional<UUID> userId = parseUuid(tenant != null ? tenant.userId() : null);
        if (orgId.isEmpty() || userId.isEmpty()) {
            return Map.of();
        }
        return withTenantOrg(
                orgId.get().toString(),
                () ->
                        fileRepository
                                .findByOrgIdAndUserIdAndStatusOrderByLogicalPathAsc(
                                        orgId.get(), userId.get(), STATUS_ACTIVE)
                                .stream()
                                .filter(file -> file.getCurrentVersionId() != null)
                                .collect(
                                        Collectors.toMap(
                                                FileEntity::getLogicalPath,
                                                FileEntity::getCurrentVersionId)));
    }

    @Transactional(readOnly = true)
    public List<FileVersionSummary> listVersions(TenantContext tenant, String logicalPath) {
        if (!properties.getFileStore().isEnabled()) {
            return List.of();
        }
        Optional<UUID> orgId = parseUuid(tenant != null ? tenant.orgId() : null);
        Optional<UUID> userId = parseUuid(tenant != null ? tenant.userId() : null);
        if (orgId.isEmpty() || userId.isEmpty()) {
            return List.of();
        }
        String path = normalizePath(logicalPath);
        return withTenantOrg(
                orgId.get().toString(),
                () ->
                        fileRepository
                                .findByOrgIdAndUserIdAndLogicalPath(orgId.get(), userId.get(), path)
                                .map(
                                        file ->
                                                fileVersionRepository
                                                        .findByFileIdAndOrgIdAndUserIdOrderByVersionNoDesc(
                                                                file.getId(),
                                                                orgId.get(),
                                                                userId.get())
                                                        .stream()
                                                        .map(v -> toVersionSummary(file, v))
                                                        .toList())
                                .orElse(List.of()));
    }

    @Transactional(propagation = Propagation.NEVER)
    public Optional<StoredFile> readVersion(TenantContext tenant, UUID versionId) {
        if (!properties.getFileStore().isEnabled() || versionId == null) {
            return Optional.empty();
        }
        Optional<UUID> orgId = parseUuid(tenant != null ? tenant.orgId() : null);
        Optional<UUID> userId = parseUuid(tenant != null ? tenant.userId() : null);
        FileObjectStore store = objectStoreProvider.getIfAvailable();
        if (orgId.isEmpty() || userId.isEmpty() || store == null) {
            return Optional.empty();
        }
        return withTenantOrg(
                orgId.get().toString(),
                () ->
                        fileVersionRepository
                                .findByIdAndOrgIdAndUserId(versionId, orgId.get(), userId.get())
                                .flatMap(
                                        version ->
                                                fileRepository
                                                        .findByIdAndOrgIdAndUserId(
                                                                version.getFileId(),
                                                                orgId.get(),
                                                                userId.get())
                                                        .map(
                                                                file ->
                                                                        new StoredFile(
                                                                                file
                                                                                        .getLogicalPath(),
                                                                                version
                                                                                        .getContentType(),
                                                                                getObject(
                                                                                        store,
                                                                                        orgId.get(),
                                                                                        version
                                                                                                .getObjectKey()),
                                                                                version
                                                                                        .getSizeBytes(),
                                                                                version
                                                                                        .getSha256()))));
    }

    @Transactional(propagation = Propagation.NEVER)
    public Optional<FileRecord> restoreVersion(
            TenantContext tenant,
            UUID agentId,
            UUID sessionId,
            UUID versionId,
            String logicalPath) {
        Optional<StoredFile> stored = readVersion(tenant, versionId);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        String path =
                logicalPath != null && !logicalPath.isBlank()
                        ? normalizePath(logicalPath)
                        : stored.get().logicalPath();
        return recordWorkspaceFile(
                tenant,
                agentId,
                sessionId,
                path,
                stored.get().content(),
                stored.get().contentType(),
                SOURCE_WORKSPACE_RESTORE,
                Map.of("restoredVersionId", versionId.toString()));
    }

    @Transactional
    public Optional<AttachmentRecord> attachFile(
            TenantContext tenant,
            UUID agentId,
            UUID sessionId,
            UUID messageId,
            UUID taskId,
            FileRecord record,
            String kind,
            Map<String, Object> metadata) {
        if (!properties.getFileStore().isEnabled() || record == null) {
            return Optional.empty();
        }
        Optional<UUID> orgId = parseUuid(tenant != null ? tenant.orgId() : null);
        Optional<UUID> userId = parseUuid(tenant != null ? tenant.userId() : null);
        if (orgId.isEmpty() || userId.isEmpty()) {
            return Optional.empty();
        }
        return withTenantOrg(
                orgId.get().toString(),
                () -> {
                    FileAttachmentEntity attachment = new FileAttachmentEntity();
                    attachment.setId(UUID.randomUUID());
                    attachment.setOrgId(orgId.get());
                    attachment.setUserId(userId.get());
                    attachment.setAgentId(agentId);
                    attachment.setSessionId(sessionId);
                    attachment.setMessageId(messageId);
                    attachment.setTaskId(taskId);
                    attachment.setFileId(record.fileId());
                    attachment.setFileVersionId(record.versionId());
                    attachment.setKind(kind != null && !kind.isBlank() ? kind : "workspace_file");
                    attachment.setMetadata(json(metadata));
                    fileAttachmentRepository.save(attachment);
                    return Optional.of(
                            new AttachmentRecord(
                                    attachment.getId(),
                                    record.fileId(),
                                    record.versionId(),
                                    record.logicalPath(),
                                    attachment.getKind()));
                });
    }

    @Transactional
    public AttachmentRecord attachExistingVersion(
            TenantContext tenant,
            UUID agentId,
            UUID sessionId,
            UUID messageId,
            String logicalPath,
            UUID versionId) {
        UUID orgId =
                parseUuid(tenant != null ? tenant.orgId() : null)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        UUID userId =
                parseUuid(tenant.userId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        String path = normalizePath(logicalPath);
        if (!path.startsWith("inputs/")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Attachment is not an uploaded input");
        }
        return withTenantOrg(
                orgId.toString(),
                () -> {
                    FileEntity file =
                            fileRepository
                                    .findByOrgIdAndUserIdAndLogicalPath(orgId, userId, path)
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND,
                                                            "Attachment file not found"));
                    FileVersionEntity version =
                            fileVersionRepository
                                    .findByIdAndOrgIdAndUserId(versionId, orgId, userId)
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND,
                                                            "Attachment version not found"));
                    if (!STATUS_ACTIVE.equals(file.getStatus())
                            || !versionId.equals(file.getCurrentVersionId())
                            || !file.getId().equals(version.getFileId())) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Attachment version is no longer current");
                    }
                    FileRecord record =
                            new FileRecord(
                                    file.getId(),
                                    versionId,
                                    path,
                                    version.getVersionNo(),
                                    version.getObjectKey(),
                                    version.getStorageBackend(),
                                    version.getSizeBytes(),
                                    version.getSha256());
                    return attachFile(
                                    tenant,
                                    agentId,
                                    sessionId,
                                    messageId,
                                    null,
                                    record,
                                    "user_upload",
                                    Map.of())
                            .orElseThrow();
                });
    }

    @Transactional(readOnly = true)
    public Map<UUID, List<MessageFile>> messageFiles(
            UUID orgId, UUID userId, List<UUID> messageIds) {
        if (messageIds.isEmpty()) {
            return Map.of();
        }
        return withTenantOrg(
                orgId.toString(),
                () -> {
                    List<FileAttachmentEntity> attachments =
                            fileAttachmentRepository.findByOrgIdAndUserIdAndMessageIds(
                                    orgId, userId, messageIds);
                    if (attachments.isEmpty()) {
                        return Map.of();
                    }
                    Map<UUID, FileVersionEntity> versions =
                            fileVersionRepository
                                    .findAllById(
                                            attachments.stream()
                                                    .map(FileAttachmentEntity::getFileVersionId)
                                                    .distinct()
                                                    .toList())
                                    .stream()
                                    .collect(Collectors.toMap(FileVersionEntity::getId, v -> v));
                    Map<UUID, List<MessageFile>> result = new LinkedHashMap<>();
                    Map<UUID, FileEntity> files = new LinkedHashMap<>();
                    for (FileAttachmentEntity attachment : attachments) {
                        FileEntity file =
                                files.computeIfAbsent(
                                        attachment.getFileId(),
                                        id ->
                                                fileRepository
                                                        .findByIdAndOrgIdAndUserId(
                                                                id, orgId, userId)
                                                        .orElse(null));
                        FileVersionEntity version = versions.get(attachment.getFileVersionId());
                        if (file == null
                                || version == null
                                || !orgId.equals(version.getOrgId())
                                || !userId.equals(version.getUserId())
                                || !file.getId().equals(version.getFileId())) {
                            continue;
                        }
                        result.computeIfAbsent(
                                        attachment.getMessageId(),
                                        id -> new java.util.ArrayList<>())
                                .add(
                                        new MessageFile(
                                                file.getLogicalPath(),
                                                version.getId().toString(),
                                                version.getSizeBytes()));
                    }
                    return result;
                });
    }

    @Transactional
    public void markDeleted(TenantContext tenant, String logicalPath) {
        markDeletedLocked(tenant, logicalPath, null, null, null);
    }

    @Transactional
    public void markDeletedForExecution(
            TenantContext tenant,
            UUID agentId,
            UUID runId,
            SessionFence fence,
            String logicalPath) {
        markDeletedLocked(
                tenant, logicalPath, agentId, runId, Objects.requireNonNull(fence, "sessionFence"));
    }

    private void markDeletedLocked(
            TenantContext tenant,
            String logicalPath,
            UUID agentId,
            UUID runId,
            SessionFence fence) {
        if (!properties.getFileStore().isEnabled()) {
            return;
        }
        Optional<UUID> orgId = parseUuid(tenant != null ? tenant.orgId() : null);
        Optional<UUID> userId = parseUuid(tenant != null ? tenant.userId() : null);
        if (orgId.isEmpty() || userId.isEmpty()) {
            return;
        }
        String path = normalizePath(logicalPath);
        withTenantOrg(
                orgId.get().toString(),
                () -> {
                    if (publications != null) {
                        lockQuotaOwners(orgId.get(), userId.get());
                        publications.requireIdlePath(orgId.get(), userId.get(), path);
                    }
                    lockExecutionFence(tenant, agentId, runId, fence);
                    fileRepository
                            .lockByOrgUserPath(orgId.get(), userId.get(), path)
                            .ifPresent(
                                    file -> {
                                        file.setStatus(STATUS_DELETED);
                                        file.setSource(SOURCE_WORKSPACE_DELETE);
                                        file.setUpdatedAt(OffsetDateTime.now());
                                        fileRepository.save(file);
                                    });
                    return null;
                });
    }

    @Transactional
    public void moveFile(
            TenantContext tenant, UUID agentId, UUID sessionId, String from, String to) {
        if (!properties.getFileStore().isEnabled()) {
            return;
        }
        Optional<UUID> orgId = parseUuid(tenant != null ? tenant.orgId() : null);
        Optional<UUID> userId = parseUuid(tenant != null ? tenant.userId() : null);
        if (orgId.isEmpty() || userId.isEmpty()) {
            return;
        }
        String sourcePath = normalizePath(from);
        String targetPath = normalizePath(to);
        withTenantOrg(
                orgId.get().toString(),
                () -> {
                    if (publications != null) {
                        lockQuotaOwners(orgId.get(), userId.get());
                        publications.requireIdlePath(orgId.get(), userId.get(), sourcePath);
                        publications.requireIdlePath(orgId.get(), userId.get(), targetPath);
                    }
                    Optional<FileEntity> sourceOpt =
                            fileRepository.lockByOrgUserPath(orgId.get(), userId.get(), sourcePath);
                    if (sourceOpt.isEmpty()) {
                        return null;
                    }
                    FileEntity source = sourceOpt.get();
                    Optional<FileVersionEntity> sourceVersion =
                            source.getCurrentVersionId() == null
                                    ? Optional.empty()
                                    : fileVersionRepository.findByIdAndOrgId(
                                            source.getCurrentVersionId(), orgId.get());
                    source.setStatus(STATUS_DELETED);
                    source.setSource(SOURCE_WORKSPACE_MOVE);
                    source.setUpdatedAt(OffsetDateTime.now());
                    fileRepository.save(source);
                    if (sourceVersion.isEmpty()) {
                        return null;
                    }
                    Optional<FileEntity> existingTarget =
                            fileRepository.lockByOrgUserPath(orgId.get(), userId.get(), targetPath);
                    FileEntity target =
                            existingTarget.orElseGet(
                                    () ->
                                            newFile(
                                                    orgId.get(),
                                                    userId.get(),
                                                    agentId,
                                                    sessionId,
                                                    targetPath,
                                                    SOURCE_WORKSPACE_MOVE));
                    if (existingTarget.isEmpty()) {
                        fileRepository.saveAndFlush(target);
                    }
                    long versionNo =
                            existingTarget.isEmpty()
                                    ? 1L
                                    : fileVersionRepository.maxVersionNo(target.getId()) + 1L;
                    FileVersionEntity copied =
                            copyVersionForMove(
                                    target,
                                    sourceVersion.get(),
                                    agentId,
                                    sessionId,
                                    versionNo,
                                    Map.of("from", sourcePath, "to", targetPath));
                    fileVersionRepository.save(copied);
                    target.setAgentId(agentId);
                    target.setSessionId(sessionId);
                    target.setCurrentVersionId(copied.getId());
                    target.setSource(SOURCE_WORKSPACE_MOVE);
                    target.setStatus(STATUS_ACTIVE);
                    target.setUpdatedAt(OffsetDateTime.now());
                    fileRepository.save(target);
                    return null;
                });
    }

    private FileEntity newFile(
            UUID orgId, UUID userId, UUID agentId, UUID sessionId, String path, String source) {
        FileEntity file = new FileEntity();
        file.setId(UUID.randomUUID());
        file.setOrgId(orgId);
        file.setUserId(userId);
        file.setAgentId(agentId);
        file.setSessionId(sessionId);
        file.setLogicalPath(path);
        file.setSource(source);
        file.setStatus(STATUS_ACTIVE);
        file.setUpdatedAt(OffsetDateTime.now());
        return file;
    }

    private FileVersionEntity newVersion(
            FileEntity file,
            UUID agentId,
            UUID sessionId,
            long versionNo,
            String objectKey,
            String backend,
            String contentType,
            long sizeBytes,
            String sha256,
            String source,
            Map<String, Object> metadata) {
        FileVersionEntity version = new FileVersionEntity();
        version.setId(UUID.randomUUID());
        version.setFileId(file.getId());
        version.setOrgId(file.getOrgId());
        version.setUserId(file.getUserId());
        version.setAgentId(agentId);
        version.setSessionId(sessionId);
        version.setVersionNo(versionNo);
        version.setObjectKey(objectKey);
        version.setStorageBackend(backend);
        version.setContentType(contentType);
        version.setSizeBytes(sizeBytes);
        version.setSha256(sha256);
        version.setSource(source);
        version.setMetadata(json(metadata));
        return version;
    }

    private Optional<FileVersionEntity> currentVersion(FileEntity file, UUID orgId) {
        return file.getCurrentVersionId() == null
                ? Optional.empty()
                : fileVersionRepository.findByIdAndOrgId(file.getCurrentVersionId(), orgId);
    }

    private FileVersionEntity copyVersionForMove(
            FileEntity target,
            FileVersionEntity source,
            UUID agentId,
            UUID sessionId,
            long versionNo,
            Map<String, Object> metadata) {
        return newVersion(
                target,
                agentId,
                sessionId,
                versionNo,
                source.getObjectKey(),
                source.getStorageBackend(),
                source.getContentType(),
                source.getSizeBytes() != null ? source.getSizeBytes() : 0L,
                source.getSha256(),
                SOURCE_WORKSPACE_MOVE,
                metadata);
    }

    private FileVersionSummary toVersionSummary(FileEntity file, FileVersionEntity version) {
        return new FileVersionSummary(
                version.getId(),
                file.getId(),
                file.getLogicalPath(),
                version.getVersionNo() != null ? version.getVersionNo() : 0L,
                Objects.equals(file.getCurrentVersionId(), version.getId()),
                version.getSizeBytes() != null ? version.getSizeBytes() : 0L,
                version.getSha256(),
                version.getContentType(),
                version.getSource(),
                version.getCreatedAt() != null ? version.getCreatedAt().toString() : null);
    }

    private void putObject(
            FileObjectStore store,
            UUID orgId,
            String objectKey,
            byte[] bytes,
            String contentType,
            String sha256) {
        try {
            store.put(new FileObject(orgId, objectKey, bytes, contentType, sha256));
        } catch (Exception e) {
            log.warn("File object write failed ({})", e.getClass().getSimpleName());
            throw new IllegalStateException("Failed to store file object");
        }
    }

    private byte[] getObject(FileObjectStore store, UUID orgId, String objectKey) {
        try {
            return store.get(orgId, objectKey);
        } catch (Exception e) {
            log.warn("File object read failed ({})", e.getClass().getSimpleName());
            throw new IllegalStateException("Failed to read file object");
        }
    }

    private String objectKey(UUID orgId, UUID userId, String sha256) {
        String prefix = properties.getFileStore().getObjectKeyPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "files/";
        }
        if (!prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        return prefix
                + "org="
                + orgId
                + "/user="
                + userId
                + "/"
                + UUID.randomUUID()
                + "-"
                + sha256.substring(0, 16);
    }

    private static Collection<UUID> currentVersionIds(List<FileEntity> files) {
        return files.stream()
                .map(FileEntity::getCurrentVersionId)
                .filter(Objects::nonNull)
                .toList();
    }

    private String json(Map<String, Object> metadata) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (metadata != null) {
            safe.putAll(metadata);
        }
        try {
            return objectMapper.writeValueAsString(safe);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static String normalizePath(String path) {
        String p = path == null ? "" : path.trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.isBlank()) {
            throw new IllegalArgumentException("logicalPath is required");
        }
        return p;
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static Optional<UUID> parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static <T> T withTenantOrg(String orgId, Supplier<T> operation) {
        String previous = TenantContextHolder.getOrgId();
        TenantContextHolder.setOrgId(orgId);
        try {
            return operation.get();
        } finally {
            TenantContextHolder.setOrgId(previous);
        }
    }

    public record FileRecord(
            UUID fileId,
            UUID versionId,
            String logicalPath,
            long versionNo,
            String objectKey,
            String storageBackend,
            long sizeBytes,
            String sha256) {}

    public record StoredFile(
            String logicalPath, String contentType, byte[] content, Long sizeBytes, String sha256) {

        public String text() {
            return new String(content != null ? content : new byte[0], StandardCharsets.UTF_8);
        }
    }

    public record CatalogFileSummary(String logicalPath, long sizeBytes) {}

    public record FileVersionSummary(
            UUID id,
            UUID fileId,
            String logicalPath,
            long versionNo,
            boolean current,
            long sizeBytes,
            String sha256,
            String contentType,
            String source,
            String createdAt) {}

    public record FileQuotaUsage(
            long userBytes,
            long userLimitBytes,
            long orgBytes,
            long orgLimitBytes,
            long maxFileBytes) {}

    public record AttachmentRecord(
            UUID id, UUID fileId, UUID fileVersionId, String logicalPath, String kind) {}

    public record MessageFile(String path, String versionId, Long sizeBytes) {}
}
