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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.core.persistence.entity.MemoryEventEntity;
import io.agentscope.saas.core.persistence.repo.MemoryEventRepository;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Persists successful {@code MEMORY.md} consolidation versions into the memory event ledger. */
@Service
public class PgMemoryConsolidationAuditSink implements MemoryConsolidator.ConsolidationSink {

    private static final Logger log = LoggerFactory.getLogger(PgMemoryConsolidationAuditSink.class);

    static final String SOURCE_WORKSPACE = "workspace";
    static final String EVENT_MEMORY_CONSOLIDATION = "memory_consolidation";
    static final String STATUS_SYNCED = "synced";

    private final MemoryEventRepository repository;
    private final ObjectMapper objectMapper;
    private final SaasProperties properties;

    public PgMemoryConsolidationAuditSink(
            MemoryEventRepository repository,
            ObjectMapper objectMapper,
            SaasProperties properties) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void onConsolidated(MemoryConsolidator.ConsolidationEvent event) {
        if (event == null) {
            return;
        }
        TenantContext tenant = TenantContext.from(event.runtimeContext());
        if (tenant == null) {
            log.debug("Skipping memory consolidation audit without tenant context");
            return;
        }
        Optional<UUID> orgId = parseUuid(tenant.orgId());
        Optional<UUID> userId = parseUuid(tenant.userId());
        if (orgId.isEmpty() || userId.isEmpty()) {
            log.debug(
                    "Skipping memory consolidation audit for non-UUID tenant context org={}"
                            + " user={}",
                    tenant.orgId(),
                    tenant.userId());
            return;
        }

        withTenantOrg(
                tenant.orgId(),
                () -> {
                    OffsetDateTime now = OffsetDateTime.now();
                    MemoryEventEntity entity = new MemoryEventEntity();
                    entity.setId(UUID.randomUUID());
                    entity.setOrgId(orgId.get());
                    entity.setUserId(userId.get());
                    entity.setAgentId(properties.getAgent().getName());
                    entity.setSessionId(
                            event.runtimeContext() != null
                                    ? event.runtimeContext().getSessionId()
                                    : null);
                    entity.setSource(SOURCE_WORKSPACE);
                    entity.setEventType(EVENT_MEMORY_CONSOLIDATION);
                    entity.setContentJson(
                            toJson(Map.of("memory_md", nullToEmpty(event.consolidatedMemory()))));
                    entity.setMetadataJson(toJson(metadataPayload(event)));
                    entity.setSyncStatus(STATUS_SYNCED);
                    entity.setSyncAttempts(0);
                    entity.setSyncedAt(now);
                    entity.setUpdatedAt(now);
                    repository.save(entity);
                    return null;
                });
    }

    private static Map<String, Object> metadataPayload(
            MemoryConsolidator.ConsolidationEvent event) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (event.previousWatermark() != null) {
            out.put("previous_watermark", event.previousWatermark().toString());
        }
        if (event.consolidatedAt() != null) {
            out.put("consolidated_at", event.consolidatedAt().toString());
        }
        out.put("previous_chars", length(event.previousMemory()));
        out.put("consolidated_chars", length(event.consolidatedMemory()));
        out.put("daily_entries_chars", length(event.dailyEntries()));
        return out;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize memory consolidation audit", e);
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

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private static int length(String value) {
        return value != null ? value.length() : 0;
    }

    private static <T> T withTenantOrg(String orgId, TenantOperation<T> operation) {
        String previous = TenantContextHolder.getOrgId();
        TenantContextHolder.setOrgId(orgId);
        try {
            return operation.run();
        } finally {
            TenantContextHolder.setOrgId(previous);
        }
    }

    @FunctionalInterface
    private interface TenantOperation<T> {
        T run();
    }
}
