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
import io.agentscope.saas.sandbox.SandboxBackendTerminator;
import io.agentscope.saas.sandbox.SandboxMetrics;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SandboxReconciliationJobTest {

    private JdbcTemplate jdbc;
    private SaasProperties properties;
    private SandboxMetrics metrics;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl(
                "jdbc:h2:mem:sandbox_reconciliation_"
                        + UUID.randomUUID().toString().replace("-", "")
                        + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                """
                CREATE TABLE sandboxes (
                    id UUID PRIMARY KEY,
                    org_id UUID NOT NULL,
                    user_id UUID NOT NULL,
                    sandbox_type VARCHAR(32) NOT NULL,
                    external_id VARCHAR(255),
                    status VARCHAR(32) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                    last_used_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                    expires_at TIMESTAMP WITH TIME ZONE,
                    backend_release_status VARCHAR(32),
                    backend_release_attempts INTEGER NOT NULL DEFAULT 0,
                    backend_released_at TIMESTAMP WITH TIME ZONE,
                    backend_release_error VARCHAR(2000)
                )
                """);
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
        Map<String, Object> row = loadRow(id);
        assertThat(row.get("status")).isEqualTo("evicted");
        assertThat(row.get("backend_release_status")).isEqualTo("succeeded");
        assertThat(row.get("backend_release_attempts")).isEqualTo(1);
        assertThat(row.get("backend_released_at")).isNotNull();
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
        Map<String, Object> row = loadRow(id);
        assertThat(row.get("backend_release_status")).isEqualTo("failed");
        assertThat(row.get("backend_release_attempts")).isEqualTo(2);
        assertThat(row.get("backend_release_error").toString()).isEqualTo("provider down");
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
        Map<String, Object> row = loadRow(id);
        assertThat(row.get("backend_release_status")).isEqualTo("unsupported");
        assertThat(row.get("backend_release_attempts")).isEqualTo(0);
        assertThat(row.get("backend_release_error").toString())
                .isEqualTo("configured terminator handles e2b");
    }

    private SandboxReconciliationJob jobWithTerminator(SandboxBackendTerminator terminator) {
        return new SandboxReconciliationJob(jdbc, properties, terminator, metrics);
    }

    private UUID insertSandbox(
            String status,
            String type,
            String externalId,
            String backendReleaseStatus,
            int attempts,
            long expiresInSeconds) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO sandboxes (
                    id, org_id, user_id, sandbox_type, external_id, status,
                    created_at, last_used_at, expires_at,
                    backend_release_status, backend_release_attempts
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
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

    private Map<String, Object> loadRow(UUID id) {
        return jdbc.queryForMap("SELECT * FROM sandboxes WHERE id = ?", id);
    }
}
