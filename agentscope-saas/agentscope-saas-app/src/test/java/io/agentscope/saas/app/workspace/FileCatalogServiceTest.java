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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.core.persistence.entity.FileEntity;
import io.agentscope.saas.core.persistence.entity.FileVersionEntity;
import io.agentscope.saas.core.persistence.repo.FileAttachmentRepository;
import io.agentscope.saas.core.persistence.repo.FileRepository;
import io.agentscope.saas.core.persistence.repo.FileVersionRepository;
import io.agentscope.saas.core.persistence.repo.OrgRepository;
import io.agentscope.saas.core.persistence.repo.UserRepository;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.storage.FileObject;
import io.agentscope.saas.storage.FileObjectStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

class FileCatalogServiceTest {

    private final FileRepository fileRepository = mock(FileRepository.class);
    private final FileVersionRepository fileVersionRepository = mock(FileVersionRepository.class);
    private final FileAttachmentRepository fileAttachmentRepository =
            mock(FileAttachmentRepository.class);
    private final OrgRepository orgRepository = mock(OrgRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final FileObjectStore objectStore = mock(FileObjectStore.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<FileObjectStore> objectStoreProvider = mock(ObjectProvider.class);

    private final SaasProperties properties = new SaasProperties();
    private final FileCatalogService service =
            new FileCatalogService(
                    fileRepository,
                    fileVersionRepository,
                    fileAttachmentRepository,
                    orgRepository,
                    userRepository,
                    objectStoreProvider,
                    new ObjectMapper(),
                    properties);

    @BeforeEach
    void configureQuotaLocks() {
        when(orgRepository.lockTenantOrg(any(UUID.class)))
                .thenReturn(
                        Optional.of(
                                mock(io.agentscope.saas.core.persistence.entity.OrgEntity.class)));
        when(userRepository.lockTenantUser(any(UUID.class), any(UUID.class)))
                .thenReturn(
                        Optional.of(
                                mock(io.agentscope.saas.core.persistence.entity.UserEntity.class)));
    }

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @Test
    void recordsNewWorkspaceFileAsVersionedObject() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        when(objectStoreProvider.getIfAvailable()).thenReturn(objectStore);
        when(objectStore.backend()).thenReturn("pg");
        when(fileRepository.lockByOrgUserPath(orgId, userId, "notes/report.md"))
                .thenReturn(Optional.empty());
        when(fileRepository.saveAndFlush(any(FileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(fileRepository.save(any(FileEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fileVersionRepository.save(any(FileVersionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Optional<FileCatalogService.FileRecord> record =
                service.recordWorkspaceFile(
                        tenant(orgId, userId),
                        agentId,
                        sessionId,
                        "/notes/report.md",
                        content,
                        "text/plain",
                        FileCatalogService.SOURCE_WORKSPACE_WRITE,
                        Map.of("api", "workspace.write"));

        assertThat(record).isPresent();
        assertThat(record.get().logicalPath()).isEqualTo("notes/report.md");
        assertThat(record.get().versionNo()).isEqualTo(1L);
        assertThat(record.get().storageBackend()).isEqualTo("pg");
        assertThat(record.get().sizeBytes()).isEqualTo(content.length);
        assertThat(TenantContextHolder.getOrgId()).isNull();

        ArgumentCaptor<FileObject> objectCaptor = ArgumentCaptor.forClass(FileObject.class);
        verify(objectStore).put(objectCaptor.capture());
        assertThat(objectCaptor.getValue().orgId()).isEqualTo(orgId);
        assertThat(objectCaptor.getValue().content()).isEqualTo(content);
        assertThat(objectCaptor.getValue().objectKey()).startsWith("files/org=" + orgId);

        ArgumentCaptor<FileVersionEntity> versionCaptor =
                ArgumentCaptor.forClass(FileVersionEntity.class);
        verify(fileVersionRepository).save(versionCaptor.capture());
        assertThat(versionCaptor.getValue().getSha256()).isEqualTo(sha256(content));
        assertThat(versionCaptor.getValue().getMetadata()).contains("workspace.write");
    }

    @Test
    void reusesCurrentVersionWhenContentHashIsUnchanged() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        byte[] content = "same".getBytes(StandardCharsets.UTF_8);
        FileEntity file = file(orgId, userId, "draft.txt", versionId, "active");
        FileVersionEntity version =
                version(file, versionId, 3L, "objects/v3", sha256(content), content.length);
        when(objectStoreProvider.getIfAvailable()).thenReturn(objectStore);
        when(fileRepository.lockByOrgUserPath(orgId, userId, "draft.txt"))
                .thenReturn(Optional.of(file));
        when(fileVersionRepository.findByIdAndOrgId(versionId, orgId))
                .thenReturn(Optional.of(version));
        when(fileRepository.save(any(FileEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<FileCatalogService.FileRecord> record =
                service.recordWorkspaceFile(
                        tenant(orgId, userId),
                        UUID.randomUUID(),
                        null,
                        "draft.txt",
                        content,
                        "text/plain",
                        FileCatalogService.SOURCE_WORKSPACE_DOWNLOAD,
                        Map.of());

        assertThat(record).isPresent();
        assertThat(record.get().versionId()).isEqualTo(versionId);
        assertThat(record.get().versionNo()).isEqualTo(3L);
        verify(objectStore, never()).put(any());
        verify(fileVersionRepository, never()).save(any());
    }

    @Test
    void readsCurrentFileFromAuthorizedObjectStore() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        byte[] content = "stored text".getBytes(StandardCharsets.UTF_8);
        FileEntity file = file(orgId, userId, "stored.txt", versionId, "active");
        FileVersionEntity version =
                version(file, versionId, 2L, "objects/v2", sha256(content), content.length);
        when(objectStoreProvider.getIfAvailable()).thenReturn(objectStore);
        when(fileRepository.findByOrgIdAndUserIdAndLogicalPath(orgId, userId, "stored.txt"))
                .thenReturn(Optional.of(file));
        when(fileVersionRepository.findByIdAndOrgId(versionId, orgId))
                .thenReturn(Optional.of(version));
        when(objectStore.get(orgId, "objects/v2")).thenReturn(content);

        Optional<FileCatalogService.StoredFile> stored =
                service.readCurrentFile(tenant(orgId, userId), "/stored.txt");

        assertThat(stored).isPresent();
        assertThat(stored.get().text()).isEqualTo("stored text");
        assertThat(stored.get().sizeBytes()).isEqualTo(content.length);
        assertThat(TenantContextHolder.getOrgId()).isNull();
    }

    @Test
    void rejectsWriteThatWouldExceedUserQuota() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        properties.getFileStore().setMaxUserBytes(5L);
        properties.getFileStore().setMaxOrgBytes(100L);
        when(objectStoreProvider.getIfAvailable()).thenReturn(objectStore);
        when(fileRepository.lockByOrgUserPath(orgId, userId, "large.txt"))
                .thenReturn(Optional.empty());
        when(fileRepository.saveAndFlush(any(FileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(fileVersionRepository.currentUsageByUser(orgId, userId)).thenReturn(4L);
        Runnable workspaceWrite = mock(Runnable.class);

        assertThatThrownBy(
                        () ->
                                service.recordWorkspaceFileWithWrite(
                                        tenant(orgId, userId),
                                        UUID.randomUUID(),
                                        null,
                                        "large.txt",
                                        new byte[] {1, 2},
                                        "text/plain",
                                        FileCatalogService.SOURCE_WORKSPACE_UPLOAD,
                                        Map.of(),
                                        workspaceWrite))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("User file quota exceeded");
        verify(objectStore, never()).put(any());
        verify(workspaceWrite, never()).run();
    }

    private static TenantContext tenant(UUID orgId, UUID userId) {
        return new TenantContext(
                orgId.toString(), userId.toString(), "member", "standard", 2, 1000);
    }

    private static FileEntity file(
            UUID orgId, UUID userId, String path, UUID currentVersionId, String status) {
        FileEntity file = new FileEntity();
        file.setId(UUID.randomUUID());
        file.setOrgId(orgId);
        file.setUserId(userId);
        file.setLogicalPath(path);
        file.setCurrentVersionId(currentVersionId);
        file.setSource(FileCatalogService.SOURCE_WORKSPACE_WRITE);
        file.setStatus(status);
        file.setUpdatedAt(OffsetDateTime.now());
        return file;
    }

    private static FileVersionEntity version(
            FileEntity file,
            UUID versionId,
            long versionNo,
            String objectKey,
            String sha256,
            long sizeBytes) {
        FileVersionEntity version = new FileVersionEntity();
        version.setId(versionId);
        version.setFileId(file.getId());
        version.setOrgId(file.getOrgId());
        version.setUserId(file.getUserId());
        version.setVersionNo(versionNo);
        version.setObjectKey(objectKey);
        version.setStorageBackend("pg");
        version.setContentType("text/plain");
        version.setSizeBytes(sizeBytes);
        version.setSha256(sha256);
        version.setSource(FileCatalogService.SOURCE_WORKSPACE_WRITE);
        version.setMetadata("{}");
        return version;
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
