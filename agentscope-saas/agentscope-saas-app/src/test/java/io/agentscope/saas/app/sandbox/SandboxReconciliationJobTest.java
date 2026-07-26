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
package io.agentscope.saas.app.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.support.MyBatisRepositoryTestSupport;
import io.agentscope.saas.app.support.TestDatabaseMapper;
import io.agentscope.saas.dal.mybatis.admin.SandboxReconciliationMapper;
import io.agentscope.saas.dal.repository.MyBatisSandboxReconciliationRepository;
import io.agentscope.saas.domain.sandbox.SandboxReconciliationRepository;
import io.agentscope.saas.sandbox.SandboxBackendTerminator;
import io.agentscope.saas.sandbox.SandboxInventoryMetrics;
import io.agentscope.saas.sandbox.SandboxMetrics;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SandboxReconciliationJobTest {

    private TestDatabaseMapper database;
    private SandboxReconciliationRepository repository;
    private SaasProperties properties;
    private SandboxMetrics metrics;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:mem:sandbox_reconciliation_"
                        + UUID.randomUUID().toString().replace("-", "")
                        + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        database = MyBatisRepositoryTestSupport.mapper(ds, TestDatabaseMapper.class);
        repository =
                new MyBatisSandboxReconciliationRepository(
                        MyBatisRepositoryTestSupport.mapper(ds, SandboxReconciliationMapper.class));
        database.createSandboxes();
        database.createReconciliationSandboxLeases();
        properties = new SaasProperties();
        properties.getSandbox().setEnabled(true);
        properties.getSandbox().setReconciliationBatchSize(20);
        properties.getSandbox().setReconciliationActiveGraceSeconds(0);
        properties.getSandbox().setBackendReleaseMaxAttempts(3);
        metrics = mock(SandboxMetrics.class);
    }

    @Test
    void evictsExpiredActiveRowsAndReleasesBackend() {
        UUID id = insertSandbox("active", "opensandbox", "os-1", null, 0, -60);
        SandboxReconciliationJob job =
                jobWithTerminator(
                        (type, externalId) -> SandboxBackendTerminator.TerminationResult.success());

        var summary = job.reconcileBatch();

        assertThat(summary.expiredActive()).isEqualTo(1);
        assertThat(summary.backendReleased()).isEqualTo(1);
        var row = database.sandboxState(id);
        assertThat(row.status()).isEqualTo("evicted");
        assertThat(row.backendReleaseStatus()).isEqualTo("succeeded");
        assertThat(row.backendReleaseAttempts()).isEqualTo(1);
        assertThat(row.backendReleasedAt()).isNotNull();
        verify(metrics).evict("opensandbox");
        verify(metrics).backendReleaseSucceeded("opensandbox");
    }

    @Test
    void retriesFailedTerminalBackendReleaseAndRecordsError() {
        UUID id = insertSandbox("released", "e2b", "e2b-1", "failed", 1, -60);
        SandboxReconciliationJob job =
                jobWithTerminator(
                        (type, externalId) ->
                                SandboxBackendTerminator.TerminationResult.failed("provider down"));

        var summary = job.reconcileBatch();

        assertThat(summary.backendFailed()).isEqualTo(1);
        var row = database.sandboxState(id);
        assertThat(row.backendReleaseStatus()).isEqualTo("failed");
        assertThat(row.backendReleaseAttempts()).isEqualTo(2);
        assertThat(row.backendReleaseError()).isEqualTo("provider down");
        verify(metrics).backendReleaseFailed("e2b");
    }

    @Test
    void unsupportedBackendIsMarkedAndNotCountedAsAttempt() {
        UUID id = insertSandbox("evicted", "docker", "container-1", null, 0, -60);
        SandboxReconciliationJob job =
                jobWithTerminator(
                        (type, externalId) ->
                                SandboxBackendTerminator.TerminationResult.unsupported(
                                        "configured terminator handles e2b"));

        var summary = job.reconcileBatch();

        assertThat(summary.backendSkipped()).isEqualTo(1);
        var row = database.sandboxState(id);
        assertThat(row.backendReleaseStatus()).isEqualTo("unsupported");
        assertThat(row.backendReleaseAttempts()).isEqualTo(0);
        assertThat(row.backendReleaseError()).isEqualTo("configured terminator handles e2b");
    }

    @Test
    void reportsCommittedInventoryAcrossTenants() {
        insertSandbox("active", "opensandbox", "os-active", null, 0, -60);
        insertSandbox("released", "e2b", "e2b-released", "succeeded", 1, -60);
        insertLease("ACTIVE", "opensandbox", "lease-active", null, 0, -60);

        assertThat(repository.countByTypeAndStatus())
                .anySatisfy(
                        count -> {
                            assertThat(count.sandboxType()).isEqualTo("opensandbox");
                            assertThat(count.status()).isEqualTo("active");
                            assertThat(count.count()).isEqualTo(2);
                        })
                .anySatisfy(
                        count -> {
                            assertThat(count.sandboxType()).isEqualTo("e2b");
                            assertThat(count.status()).isEqualTo("released");
                            assertThat(count.count()).isEqualTo(1);
                        });
        assertThat(repository.countExpiredActiveByType(OffsetDateTime.now()))
                .singleElement()
                .satisfies(
                        count -> {
                            assertThat(count.sandboxType()).isEqualTo("opensandbox");
                            assertThat(count.count()).isEqualTo(2);
                        });
    }

    @Test
    void expiresAndReleasesOrchestrationLeaseAcrossTenantBoundary() {
        UUID id = insertLease("ACTIVE", "opensandbox", "lease-os-1", null, 0, -60);
        SandboxReconciliationJob job =
                jobWithTerminator(
                        (type, externalId) -> SandboxBackendTerminator.TerminationResult.success());

        var summary = job.reconcileBatch();

        assertThat(summary.expiredLeases()).isEqualTo(1);
        assertThat(summary.leaseReleased()).isEqualTo(1);
        var row = database.orchestrationLeaseState(id);
        assertThat(row.status()).isEqualTo("RELEASED");
        assertThat(row.releaseAttempts()).isEqualTo(1);
        assertThat(row.releasedAt()).isNotNull();
        assertThat(row.releaseError()).isNull();
        verify(metrics).backendReleaseSucceeded("opensandbox");
    }

    @Test
    void retriesFailedOrchestrationLeaseReleaseWithBoundedAttemptCount() {
        UUID id = insertLease("RELEASE_FAILED", "cube", "cube-1", "provider down", 1, -60);
        SandboxReconciliationJob job =
                jobWithTerminator(
                        (type, externalId) ->
                                SandboxBackendTerminator.TerminationResult.failed("still down"));

        var summary = job.reconcileBatch();

        assertThat(summary.leaseFailed()).isEqualTo(1);
        var row = database.orchestrationLeaseState(id);
        assertThat(row.status()).isEqualTo("RELEASE_FAILED");
        assertThat(row.releaseAttempts()).isEqualTo(2);
        assertThat(row.releaseError()).isEqualTo("still down");
        verify(metrics).backendReleaseFailed("cube");
    }

    private SandboxReconciliationJob jobWithTerminator(SandboxBackendTerminator terminator) {
        return new SandboxReconciliationJob(
                repository, properties, terminator, metrics, mock(SandboxInventoryMetrics.class));
    }

    private UUID insertSandbox(
            String status,
            String type,
            String externalId,
            String backendReleaseStatus,
            int attempts,
            long expiresInSeconds) {
        UUID id = UUID.randomUUID();
        database.insertSandbox(
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                type,
                externalId,
                status,
                OffsetDateTime.now().minusSeconds(120),
                OffsetDateTime.now().minusSeconds(60),
                OffsetDateTime.now().plusSeconds(expiresInSeconds),
                backendReleaseStatus,
                attempts);
        return id;
    }

    private UUID insertLease(
            String status,
            String providerId,
            String providerSandboxId,
            String releaseError,
            int attempts,
            long expiresInSeconds) {
        UUID id = UUID.randomUUID();
        database.insertReconciliationSandboxLease(
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                providerId,
                providerSandboxId,
                status,
                OffsetDateTime.now().plusSeconds(expiresInSeconds),
                OffsetDateTime.now().minusSeconds(120),
                releaseError,
                attempts);
        return id;
    }
}
