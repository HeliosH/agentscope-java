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
import io.agentscope.saas.core.persistence.entity.FileAttachmentEntity;
import io.agentscope.saas.core.persistence.entity.FileEntity;
import io.agentscope.saas.core.persistence.entity.FileVersionEntity;
import io.agentscope.saas.core.persistence.repo.FileAttachmentRepository;
import io.agentscope.saas.core.persistence.repo.FileRepository;
import io.agentscope.saas.core.persistence.repo.FileVersionRepository;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable file catalog for assistant workspaces.
 *
 * <p>PostgreSQL owns metadata, versioning and authorization. File bytes are immutable objects in the
 * configured {@link FileObjectStore}: MinIO/S3 in production, PG BYTEA fallback in local/test.
 */
@Service
public class FileCatalogService {

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
    private final ObjectProvider<FileObjectStore> objectStoreProvider;
    private final ObjectMapper objectMapper;
    private final SaasProperties properties;

    public FileCatalogService(
            FileRepository fileRepository,
            FileVersionRepository fileVersionRepository,
            FileAttachmentRepository fileAttachmentRepository,
            ObjectProvider<FileObjectStore> objectStoreProvider,
            ObjectMapper objectMapper,
            SaasProperties properties) {
        this.fileRepository = fileRepository;
        this.fileVersionRepository = fileVersionRepository;
        this.fileAttachmentRepository = fileAttachmentRepository;
        this.objectStoreProvider = objectStoreProvider;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Transactional
    public Optional<FileRecord> recordWorkspaceFile(
            TenantContext tenant,
            UUID agentId,
            UUID sessionId,
            String logicalPath,
            byte[] content,
            String contentType,
            String source,
            Map<String, Object> metadata) {
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
        byte[] bytes = content != null ? content : new byte[0];
        String sha256 = sha256(bytes);

        return withTenantOrg(
                orgId.get().toString(),
                () -> {
                    Optional<FileEntity> existing =
                            fileRepository.lockByOrgUserPath(orgId.get(), userId.get(), path);
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
                    if (existing.isEmpty()) {
                        fileRepository.saveAndFlush(file);
                    }
                    Optional<FileVersionEntity> current = currentVersion(file, orgId.get());
                    if (current.isPresent() && sha256.equals(current.get().getSha256())) {
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
                    String objectKey = objectKey(orgId.get(), userId.get(), sha256);
                    putObject(store, orgId.get(), objectKey, bytes, contentType, sha256);
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
                                    store.backend(),
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
                                    store.backend(),
                                    bytes.length,
                                    sha256));
                });
    }

    @Transactional(readOnly = true)
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

    @Transactional(readOnly = true)
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

    @Transactional
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
    public void markDeleted(TenantContext tenant, String logicalPath) {
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
            throw new IllegalStateException("Failed to store file object: " + objectKey, e);
        }
    }

    private byte[] getObject(FileObjectStore store, UUID orgId, String objectKey) {
        try {
            return store.get(orgId, objectKey);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read file object: " + objectKey, e);
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

    public record AttachmentRecord(
            UUID id, UUID fileId, UUID fileVersionId, String logicalPath, String kind) {}
}
