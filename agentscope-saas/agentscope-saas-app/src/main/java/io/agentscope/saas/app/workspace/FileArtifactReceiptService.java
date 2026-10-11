/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.saas.app.workspace.FileCatalogService.FileRecord;
import io.agentscope.saas.domain.orchestration.RunArtifactRepository;
import io.agentscope.saas.domain.orchestration.RunArtifactRepository.NewRunArtifact;
import io.agentscope.saas.domain.orchestration.RunArtifactRepository.RunArtifact;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.NewOutboxMessage;
import io.agentscope.saas.domain.workspace.FilePublicationRepository;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Intent;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Publication;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** File version, attempt artifact and delivery intent share the caller's short commit transaction. */
@Service
public class FileArtifactReceiptService {
    public static final String ARTIFACT_TYPE = "WORKSPACE_FILE";
    public static final String EVENT_TYPE = "workspace_file_published";
    private final FilePublicationRepository publications;
    private final RunArtifactRepository artifacts;
    private final RunOrchestrationRepository runs;
    private final ObjectMapper mapper;
    private final WorkspaceManifestRepository manifests;

    public FileArtifactReceiptService(
            FilePublicationRepository publications,
            RunArtifactRepository artifacts,
            RunOrchestrationRepository runs,
            ObjectMapper mapper,
            WorkspaceManifestRepository manifests) {
        this.publications = publications;
        this.artifacts = artifacts;
        this.runs = runs;
        this.mapper = mapper;
        this.manifests = manifests;
    }

    public void commit(Publication p, FileRecord file) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException(
                    "Artifact receipt requires the publication transaction");
        if (p.runId() == null) return;
        Intent intent = publications.intent(p.orgId(), p.userId(), p.id()).orElseThrow();
        if (intent.execution() == null)
            throw new IllegalStateException("Artifact receipt execution scope unavailable");
        if (!Objects.equals(p.logicalPath(), file.logicalPath())
                || !Objects.equals(p.objectKey(), file.objectKey())
                || !Objects.equals(p.backend(), file.storageBackend())
                || !Objects.equals(p.sha256(), file.sha256())
                || p.sizeBytes() != file.sizeBytes())
            throw new IllegalStateException("FILE_ARTIFACT_RECEIPT_MISMATCH");
        UUID artifactId = artifactId(intent.attemptId(), file.versionId());
        UUID eventId =
                UUID.nameUUIDFromBytes(
                        (EVENT_TYPE + ":" + artifactId).getBytes(StandardCharsets.UTF_8));
        RunArtifact existing = artifacts.findById(artifactId, p.orgId()).orElse(null);
        if (existing != null) {
            if (!Objects.equals(existing.runId(), p.runId())
                    || !Objects.equals(existing.taskId(), intent.taskId())
                    || !Objects.equals(existing.attemptId(), intent.attemptId())
                    || !Objects.equals(existing.fileId(), file.fileId())
                    || !Objects.equals(existing.fileVersionId(), file.versionId())
                    || !Objects.equals(existing.logicalPath(), file.logicalPath())
                    || !ARTIFACT_TYPE.equals(existing.artifactType()))
                throw new IllegalStateException("FILE_ARTIFACT_RECEIPT_MISMATCH");
        } else {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("publicationId", p.id());
            evidence.put("logicalPath", file.logicalPath());
            evidence.put("versionNo", file.versionNo());
            evidence.put("sha256", file.sha256());
            evidence.put("sizeBytes", file.sizeBytes());
            evidence.put("storageBackend", file.storageBackend());
            evidence.put("producedInRun", producedInRun(intent, file));
            if (artifacts.insert(
                            new NewRunArtifact(
                                    artifactId,
                                    p.orgId(),
                                    p.runId(),
                                    intent.taskId(),
                                    intent.attemptId(),
                                    file.fileId(),
                                    file.versionId(),
                                    file.logicalPath(),
                                    ARTIFACT_TYPE,
                                    json(evidence),
                                    OffsetDateTime.now()))
                    != 1) throw new IllegalStateException("Artifact receipt insert failed");
            // No body, object credentials or arbitrary tool metadata is delivered to the outbox.
            runs.insertOutbox(
                    new NewOutboxMessage(
                            eventId,
                            p.orgId(),
                            artifactId,
                            "run_artifact",
                            EVENT_TYPE,
                            json(
                                    Map.of(
                                            "eventId",
                                            eventId,
                                            "artifactId",
                                            artifactId,
                                            "runId",
                                            p.runId(),
                                            "taskId",
                                            intent.taskId(),
                                            "attemptId",
                                            intent.attemptId(),
                                            "fileId",
                                            file.fileId(),
                                            "fileVersionId",
                                            file.versionId()))));
        }
        if (publications.linkArtifactReceipt(p, artifactId, eventId) != 1)
            throw new IllegalStateException("FILE_ARTIFACT_RECEIPT_MISMATCH");
        try {
            String manifestId =
                    mapper.readTree(intent.metadataJson()).path("workspaceManifestId").asText("");
            if (!manifestId.isEmpty()
                    && manifests.recordPublication(UUID.fromString(manifestId), p, intent) != 1)
                throw new IllegalStateException("WORKSPACE_MANIFEST_PUBLICATION_MISMATCH");
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Invalid publication intent metadata", error);
        }
    }

    private boolean producedInRun(Intent intent, FileRecord file) {
        try {
            var metadata = mapper.readTree(intent.metadataJson());
            if (metadata.path("runBaselineCaptured").asBoolean(false))
                return !file.versionId()
                        .toString()
                        .equals(metadata.path("runBaselineVersionId").asText());
            return !Objects.equals(intent.baseVersionId(), file.versionId());
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Invalid publication intent metadata", error);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Artifact receipt serialization failed", error);
        }
    }

    public static UUID artifactId(UUID attempt, UUID version) {
        return UUID.nameUUIDFromBytes(
                ("workspace-file:" + attempt + ":" + version).getBytes(StandardCharsets.UTF_8));
    }
}
