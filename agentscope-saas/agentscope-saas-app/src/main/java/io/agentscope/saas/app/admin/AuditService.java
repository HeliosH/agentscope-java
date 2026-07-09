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
package io.agentscope.saas.app.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.saas.core.persistence.entity.AuditLogEntity;
import io.agentscope.saas.core.persistence.repo.AuditLogRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Best-effort audit writer for admin and security-sensitive operations. Audit failures are logged
 * and swallowed so the primary operation is not made less available by the audit sink.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final Set<String> SECRET_HINTS =
            Set.of("password", "secret", "token", "apikey", "api_key", "accesskey", "credential");
    private static final String REDACTED = "[REDACTED]";

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void record(UUID orgId, UUID actorId, String action, String resource) {
        record(orgId, actorId, action, resource, Map.of());
    }

    public void record(
            UUID orgId, UUID actorId, String action, String resource, Map<String, ?> detail) {
        if (orgId == null || action == null || action.isBlank()) {
            return;
        }
        try {
            AuditLogEntity entity = new AuditLogEntity();
            entity.setOrgId(orgId);
            entity.setActor(actorId);
            entity.setAction(trim(action, 64));
            entity.setResource(trim(resource, 128));
            entity.setDetail(serializeDetail(detail));
            repository.save(entity);
        } catch (RuntimeException e) {
            log.warn("Failed to write audit event {} on {}: {}", action, resource, e.getMessage());
        }
    }

    private String serializeDetail(Map<String, ?> detail) {
        try {
            return objectMapper.writeValueAsString(redact(detail != null ? detail : Map.of()));
        } catch (JsonProcessingException e) {
            return "{\"serializationError\":\"" + sanitize(e.getMessage()) + "\"}";
        }
    }

    private Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                out.put(key, isSecretKey(key) ? REDACTED : redact(entry.getValue()));
            }
            return out;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(this::redact).toList();
        }
        return value;
    }

    private static boolean isSecretKey(String key) {
        String normalized =
                key == null ? "" : key.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
        return SECRET_HINTS.stream().anyMatch(normalized::contains);
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
