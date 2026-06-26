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
package io.agentscope.saas.app.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.core.persistence.entity.MemoryEventEntity;
import io.agentscope.saas.core.persistence.repo.MemoryEventRepository;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PgMemoryConsolidationAuditSinkTest {

    private final MemoryEventRepository repository = mock(MemoryEventRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SaasProperties properties = new SaasProperties();
    private final PgMemoryConsolidationAuditSink sink =
            new PgMemoryConsolidationAuditSink(repository, objectMapper, properties);

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @Test
    void persistsConsolidatedMemoryAsSyncedWorkspaceEvent() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        properties.getAgent().setName("enterprise-assistant");
        RuntimeContext rc =
                RuntimeContext.builder()
                        .sessionId("session-42")
                        .put(
                                TenantContext.class,
                                new TenantContext(
                                        orgId.toString(),
                                        userId.toString(),
                                        "member",
                                        "standard",
                                        2,
                                        1000))
                        .build();

        sink.onConsolidated(
                new MemoryConsolidator.ConsolidationEvent(
                        rc,
                        "# Previous\n",
                        "# Consolidated\n",
                        "### 2026-06-26.md\n- Durable fact",
                        Instant.parse("2026-06-25T12:00:00Z"),
                        Instant.parse("2026-06-26T09:30:00Z")));

        ArgumentCaptor<MemoryEventEntity> captor = ArgumentCaptor.forClass(MemoryEventEntity.class);
        verify(repository).save(captor.capture());
        MemoryEventEntity entity = captor.getValue();
        assertThat(entity.getId()).isNotNull();
        assertThat(entity.getOrgId()).isEqualTo(orgId);
        assertThat(entity.getUserId()).isEqualTo(userId);
        assertThat(entity.getAgentId()).isEqualTo("enterprise-assistant");
        assertThat(entity.getSessionId()).isEqualTo("session-42");
        assertThat(entity.getSource()).isEqualTo(PgMemoryConsolidationAuditSink.SOURCE_WORKSPACE);
        assertThat(entity.getEventType())
                .isEqualTo(PgMemoryConsolidationAuditSink.EVENT_MEMORY_CONSOLIDATION);
        assertThat(entity.getSyncStatus()).isEqualTo(PgMemoryConsolidationAuditSink.STATUS_SYNCED);
        assertThat(entity.getSyncAttempts()).isZero();
        assertThat(entity.getSyncedAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isNotNull();
        assertThat(TenantContextHolder.getOrgId()).isNull();

        Map<String, Object> content =
                objectMapper.readValue(entity.getContentJson(), new TypeReference<>() {});
        assertThat(content).containsEntry("memory_md", "# Consolidated\n");

        Map<String, Object> metadata =
                objectMapper.readValue(entity.getMetadataJson(), new TypeReference<>() {});
        assertThat(metadata)
                .containsEntry("previous_watermark", "2026-06-25T12:00:00Z")
                .containsEntry("consolidated_at", "2026-06-26T09:30:00Z")
                .containsEntry("previous_chars", 11)
                .containsEntry("consolidated_chars", 15)
                .containsEntry("daily_entries_chars", 32);
    }

    @Test
    void skipsNonUuidTenantContext() {
        RuntimeContext rc =
                RuntimeContext.builder()
                        .put(
                                TenantContext.class,
                                new TenantContext("org", "user", "member", "standard", 1, 1000))
                        .build();

        sink.onConsolidated(
                new MemoryConsolidator.ConsolidationEvent(
                        rc, "", "# Memory", "", Instant.EPOCH, Instant.now()));

        verifyNoInteractions(repository);
    }
}
