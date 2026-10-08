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
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.model.MemoryEventEntity;
import io.agentscope.saas.domain.repository.MemoryEventRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
                    UUID eventId = sourceId(tenant, agentName, sessionId, metadata);
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
                    if (!repository.appendIfAbsent(entity)) {
                        MemoryEventEntity existing = repository.findById(eventId).orElseThrow();
                        if (!Objects.equals(existing.getOrgId(), entity.getOrgId())
                                || !Objects.equals(existing.getUserId(), entity.getUserId())
                                || !Objects.equals(existing.getAgentId(), entity.getAgentId())
                                || !Objects.equals(existing.getSessionId(), entity.getSessionId())
                                || !SOURCE_MEM0.equals(existing.getSource())
                                || !EVENT_CONVERSATION.equals(existing.getEventType())
                                || !sameContent(
                                        existing.getContentJson(), entity.getContentJson())) {
                            throw new IllegalStateException("MEMORY_SOURCE_ID_CONTENT_CONFLICT");
                        }
                    }
                    return Optional.of(new MemoryEventRef(eventId, tenant.orgId()));
                });
    }

    private UUID sourceId(
            TenantContext tenant, String agent, String session, Map<String, Object> metadata) {
        Object ids = metadata == null ? null : metadata.get("source_message_ids");
        if (!(ids instanceof List<?> list) || list.isEmpty()) return UUID.randomUUID();
        var identity = new LinkedHashMap<String, Object>();
        identity.put("schema", "memory-source-v1");
        identity.put("org", tenant.orgId());
        identity.put("user", tenant.userId());
        identity.put("agent", agent);
        identity.put("session", session);
        identity.put("ids", list);
        return UUID.nameUUIDFromBytes(toJson(identity).getBytes(StandardCharsets.UTF_8));
    }

    private boolean sameContent(String left, String right) {
        try {
            return objectMapper.readTree(left).equals(objectMapper.readTree(right));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Invalid persisted memory source", error);
        }
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
