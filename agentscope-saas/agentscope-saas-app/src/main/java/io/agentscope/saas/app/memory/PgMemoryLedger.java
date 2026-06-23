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
import io.agentscope.core.memory.mem0.Mem0Message;
import io.agentscope.saas.core.persistence.entity.MemoryEventEntity;
import io.agentscope.saas.core.persistence.repo.MemoryEventRepository;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** PostgreSQL-backed implementation of the memory source ledger. */
@Service
public class PgMemoryLedger implements MemoryLedger {

    private static final Logger log = LoggerFactory.getLogger(PgMemoryLedger.class);

    private static final String SOURCE_MEM0 = "mem0";
    private static final String EVENT_CONVERSATION = "conversation";
    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_SYNCED = "synced";
    private static final String STATUS_FAILED = "failed";
    private static final int MAX_ERROR_LENGTH = 2000;

    private final MemoryEventRepository repository;
    private final ObjectMapper objectMapper;

    public PgMemoryLedger(MemoryEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<MemoryEventRef> recordPending(
            TenantContext tenant,
            String agentName,
            String sessionId,
            List<Mem0Message> messages,
            Map<String, Object> metadata) {
        if (tenant == null || messages == null || messages.isEmpty()) {
            return Optional.empty();
        }
        Optional<UUID> orgId = parseUuid(tenant.orgId());
        Optional<UUID> userId = parseUuid(tenant.userId());
        if (orgId.isEmpty() || userId.isEmpty()) {
            log.debug(
                    "Skipping memory ledger for non-UUID tenant context org={} user={}",
                    tenant.orgId(),
                    tenant.userId());
            return Optional.empty();
        }

        return withTenantOrg(
                tenant.orgId(),
                () -> {
                    MemoryEventEntity entity = new MemoryEventEntity();
                    UUID eventId = UUID.randomUUID();
                    entity.setId(eventId);
                    entity.setOrgId(orgId.get());
                    entity.setUserId(userId.get());
                    entity.setAgentId(agentName);
                    entity.setSessionId(sessionId);
                    entity.setSource(SOURCE_MEM0);
                    entity.setEventType(EVENT_CONVERSATION);
                    entity.setContentJson(toJson(contentPayload(messages)));
                    entity.setMetadataJson(toJson(metadataPayload(agentName, sessionId, metadata)));
                    entity.setSyncStatus(STATUS_PENDING);
                    entity.setSyncAttempts(0);
                    entity.setUpdatedAt(OffsetDateTime.now());
                    repository.save(entity);
                    return Optional.of(new MemoryEventRef(eventId, tenant.orgId()));
                });
    }

    @Override
    public void markSynced(MemoryEventRef ref) {
        if (ref == null) {
            return;
        }
        withTenantOrg(
                ref.orgId(),
                () -> {
                    repository
                            .findById(ref.id())
                            .ifPresent(
                                    entity -> {
                                        entity.setSyncStatus(STATUS_SYNCED);
                                        entity.setSyncAttempts(entity.getSyncAttempts() + 1);
                                        entity.setSyncedAt(OffsetDateTime.now());
                                        entity.setLastError(null);
                                        entity.setUpdatedAt(OffsetDateTime.now());
                                        repository.save(entity);
                                    });
                    return null;
                });
    }

    @Override
    public void markFailed(MemoryEventRef ref, Throwable error) {
        if (ref == null) {
            return;
        }
        withTenantOrg(
                ref.orgId(),
                () -> {
                    repository
                            .findById(ref.id())
                            .ifPresent(
                                    entity -> {
                                        entity.setSyncStatus(STATUS_FAILED);
                                        entity.setSyncAttempts(entity.getSyncAttempts() + 1);
                                        entity.setLastError(truncate(errorMessage(error)));
                                        entity.setUpdatedAt(OffsetDateTime.now());
                                        repository.save(entity);
                                    });
                    return null;
                });
    }

    private static Map<String, Object> contentPayload(List<Mem0Message> messages) {
        List<Map<String, Object>> serialized =
                messages.stream()
                        .map(
                                msg -> {
                                    Map<String, Object> item = new LinkedHashMap<>();
                                    item.put("role", msg.getRole());
                                    item.put("content", msg.getContent());
                                    if (msg.getName() != null) {
                                        item.put("name", msg.getName());
                                    }
                                    return item;
                                })
                        .toList();
        return Map.of("messages", serialized);
    }

    private static Map<String, Object> metadataPayload(
            String agentName, String sessionId, Map<String, Object> metadata) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (metadata != null) {
            out.putAll(metadata);
        }
        out.put("agent_id", agentName);
        if (sessionId != null) {
            out.put("session_id", sessionId);
        }
        return out;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize memory ledger payload", e);
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

    private static String errorMessage(Throwable error) {
        if (error == null) {
            return null;
        }
        return error.getMessage() != null ? error.getMessage() : error.getClass().getName();
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_ERROR_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_LENGTH);
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
