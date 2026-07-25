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

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.workspace.FileObjectGcRepository;
import io.agentscope.saas.domain.workspace.FileObjectGcRepository.FileReference;
import io.agentscope.saas.domain.workspace.FileObjectGcRepository.ObjectReference;
import io.agentscope.saas.storage.FileObjectStore;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/** Applies file retention and retries physical deletion of objects no longer referenced by metadata. */
@Component
public class FileObjectGcJob {

    private static final Logger log = LoggerFactory.getLogger(FileObjectGcJob.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final FileObjectGcRepository repository;
    private final TransactionOperations transactions;
    private final ObjectProvider<FileObjectStore> objectStoreProvider;
    private final SaasProperties properties;

    public FileObjectGcJob(
            FileObjectGcRepository repository,
            @Qualifier("adminTransactionOperations") TransactionOperations transactions,
            ObjectProvider<FileObjectStore> objectStoreProvider,
            SaasProperties properties) {
        this.repository = repository;
        this.transactions = transactions;
        this.objectStoreProvider = objectStoreProvider;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${saas.file-store.gc-fixed-delay-seconds:3600}",
            timeUnit = TimeUnit.SECONDS)
    public void collectScheduled() {
        SaasProperties.FileStore cfg = properties.getFileStore();
        if (!cfg.isEnabled() || !cfg.isGcEnabled()) {
            return;
        }
        try {
            GcSummary summary = collectOnce();
            if (summary.total() > 0) {
                log.info(
                        "File GC completed deletedFiles={} prunedVersions={} objectsDeleted={}"
                                + " objectsRetained={} failures={}",
                        summary.deletedFiles(),
                        summary.prunedVersions(),
                        summary.objectsDeleted(),
                        summary.objectsRetained(),
                        summary.failures());
            }
        } catch (RuntimeException e) {
            log.warn("File GC scan failed: {}", e.getMessage());
        }
    }

    GcSummary collectOnce() {
        SaasProperties.FileStore cfg = properties.getFileStore();
        int batchSize = Math.max(1, cfg.getGcBatchSize());
        int deleted = purgeDeletedFiles(cfg.getDeletedRetentionDays(), batchSize);
        int pruned = pruneOldVersions(cfg.getMaxVersionsPerFile(), batchSize);
        ObjectCounts objects = deleteQueuedObjects(batchSize, Math.max(1, cfg.getGcMaxAttempts()));
        return new GcSummary(
                deleted, pruned, objects.deleted(), objects.retained(), objects.failed());
    }

    private int purgeDeletedFiles(int retentionDays, int limit) {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(Math.max(0, retentionDays));
        Integer purged =
                transactions.execute(
                        status -> {
                            List<FileReference> files = repository.findDeletedFiles(cutoff, limit);
                            OffsetDateTime now = OffsetDateTime.now();
                            int processed = 0;
                            for (FileReference file : files) {
                                if (repository.claimDeletedFile(file.id()) != 1) {
                                    continue;
                                }
                                for (ObjectReference object :
                                        repository.findFileObjects(file.id())) {
                                    repository.enqueueObject(object, now);
                                }
                                repository.deleteFileAttachments(file.id());
                                repository.deleteFileVersions(file.id());
                                repository.deleteFile(file.id());
                                processed++;
                            }
                            return processed;
                        });
        return purged != null ? purged : 0;
    }

    private int pruneOldVersions(int maxVersions, int limit) {
        if (maxVersions <= 0) {
            return 0;
        }
        Integer pruned =
                transactions.execute(
                        status -> {
                            List<ObjectReference> versions =
                                    repository.findPrunableVersions(maxVersions, limit);
                            OffsetDateTime now = OffsetDateTime.now();
                            int processed = 0;
                            for (ObjectReference version : versions) {
                                if (repository.claimPrunableVersion(version.id()) != 1) {
                                    continue;
                                }
                                repository.enqueueObject(version, now);
                                repository.deleteFileVersion(version.id());
                                processed++;
                            }
                            return processed;
                        });
        return pruned != null ? pruned : 0;
    }

    private ObjectCounts deleteQueuedObjects(int limit, int maxAttempts) {
        FileObjectStore store = objectStoreProvider.getIfAvailable();
        if (store == null) {
            return new ObjectCounts(0, 0, 0);
        }
        List<ObjectReference> candidates = repository.findDeletionCandidates(maxAttempts, limit);
        int deleted = 0;
        int retained = 0;
        int failed = 0;
        for (ObjectReference candidate : candidates) {
            if (repository.claimDeletion(candidate.id(), OffsetDateTime.now()) == 0) {
                continue;
            }
            long references =
                    repository.countObjectReferences(candidate.orgId(), candidate.objectKey());
            if (references > 0) {
                markTerminal(candidate.id(), "referenced", null);
                retained++;
                continue;
            }
            if (!store.backend().equalsIgnoreCase(candidate.storageBackend())) {
                markFailed(
                        candidate.id(),
                        "Configured backend "
                                + store.backend()
                                + " cannot delete "
                                + candidate.storageBackend()
                                + " object");
                failed++;
                continue;
            }
            try {
                withTenantOrg(
                        candidate.orgId(),
                        () -> {
                            store.delete(candidate.orgId(), candidate.objectKey());
                            return null;
                        });
                markTerminal(candidate.id(), "succeeded", null);
                deleted++;
            } catch (Exception e) {
                markFailed(candidate.id(), e.getMessage());
                failed++;
            }
        }
        return new ObjectCounts(deleted, retained, failed);
    }

    private void markTerminal(UUID id, String status, String error) {
        repository.recordDeletion(id, status, truncate(error), OffsetDateTime.now());
    }

    private void markFailed(UUID id, String error) {
        markTerminal(id, "failed", error);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    private static <T> T withTenantOrg(UUID orgId, SqlSupplier<T> supplier) throws Exception {
        String previous = TenantContextHolder.getOrgId();
        try {
            TenantContextHolder.setOrgId(orgId.toString());
            return supplier.get();
        } finally {
            if (previous == null) {
                TenantContextHolder.clear();
            } else {
                TenantContextHolder.setOrgId(previous);
            }
        }
    }

    record GcSummary(
            int deletedFiles,
            int prunedVersions,
            int objectsDeleted,
            int objectsRetained,
            int failures) {
        int total() {
            return deletedFiles + prunedVersions + objectsDeleted + objectsRetained + failures;
        }
    }

    private record ObjectCounts(int deleted, int retained, int failed) {}

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws Exception;
    }
}
