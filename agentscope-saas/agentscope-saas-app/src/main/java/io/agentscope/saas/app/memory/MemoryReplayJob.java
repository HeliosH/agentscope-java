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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.memory.mem0.Mem0AddRequest;
import io.agentscope.core.memory.mem0.Mem0Client;
import io.agentscope.core.memory.mem0.Mem0Message;
import io.agentscope.saas.app.config.SaasProperties;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Replays pending/failed long-term-memory ledger rows into Mem0.
 *
 * <p>The ledger is the source of truth. Mem0 is a derived semantic index, so transient Mem0 outages
 * should only leave replayable rows in {@code memory_events}, never drop user memory. This job uses
 * the admin/bypass DataSource intentionally: background system projection has to scan rows across
 * tenants, while request-time tenant access remains on the RLS-wrapped primary DataSource.
 */
@Component
public class MemoryReplayJob {

    private static final Logger log = LoggerFactory.getLogger(MemoryReplayJob.class);

    private static final String SOURCE_MEM0 = "mem0";
    private static final String EVENT_CONVERSATION = "conversation";
    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_SYNCING = "syncing";
    private static final String STATUS_SYNCED = "synced";
    private static final int MAX_ERROR_LENGTH = 2000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final SaasProperties properties;
    private final Mem0Client mem0Client;

    @Autowired
    public MemoryReplayJob(
            @Qualifier("adminDataSource") DataSource adminDataSource,
            ObjectMapper objectMapper,
            SaasProperties properties) {
        this(
                new JdbcTemplate(adminDataSource),
                objectMapper,
                properties,
                buildClient(properties.getLtm()));
    }

    MemoryReplayJob(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            SaasProperties properties,
            Mem0Client mem0Client) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.mem0Client = mem0Client;
    }

    @Scheduled(fixedDelayString = "${saas.ltm.replay-fixed-delay-seconds:60}000")
    public void replayScheduled() {
        SaasProperties.Ltm ltm = properties.getLtm();
        if (!ltm.isEnabled()
                || !ltm.isReplayEnabled()
                || mem0Client == null
                || ltm.getMem0BaseUrl() == null
                || ltm.getMem0BaseUrl().isBlank()) {
            return;
        }
        try {
            int replayed = replayBatch();
            if (replayed > 0) {
                log.info("Memory replay projected {} event(s)", replayed);
            }
        } catch (RuntimeException e) {
            log.warn("Memory replay scan failed: {}", e.getMessage());
        }
    }

    int replayBatch() {
        SaasProperties.Ltm ltm = properties.getLtm();
        int batchSize = Math.max(1, ltm.getReplayBatchSize());
        int maxAttempts = Math.max(1, ltm.getReplayMaxAttempts());
        OffsetDateTime staleBefore =
                OffsetDateTime.now().minusSeconds(Math.max(1L, ltm.getReplayStaleSeconds()));
        List<Candidate> candidates = loadCandidates(batchSize, maxAttempts, staleBefore);
        int replayed = 0;
        for (Candidate candidate : candidates) {
            if (!claim(candidate.id())) {
                continue;
            }
            try {
                mem0Client.add(toAddRequest(candidate)).block();
                markSynced(candidate.id());
                replayed++;
            } catch (Exception e) {
                markFailed(candidate.id(), e);
                log.warn(
                        "Memory replay failed for event {} org={} user={}: {}",
                        candidate.id(),
                        candidate.orgId(),
                        candidate.userId(),
                        e.getMessage());
            }
        }
        return replayed;
    }

    private List<Candidate> loadCandidates(
            int batchSize, int maxAttempts, OffsetDateTime staleBefore) {
        String sql =
                """
                SELECT id, org_id, user_id, agent_id, session_id, content_json, metadata_json
                  FROM memory_events
                 WHERE source = ?
                   AND event_type = ?
                   AND sync_attempts < ?
                   AND (
                        sync_status IN (?, ?)
                        OR (sync_status = ? AND updated_at < ?)
                   )
                 ORDER BY created_at ASC
                 LIMIT ?
                """;
        return jdbc.query(
                sql,
                this::mapCandidate,
                SOURCE_MEM0,
                EVENT_CONVERSATION,
                maxAttempts,
                STATUS_PENDING,
                STATUS_FAILED,
                STATUS_SYNCING,
                staleBefore,
                batchSize);
    }

    private Candidate mapCandidate(ResultSet rs, int rowNum) throws SQLException {
        return new Candidate(
                rs.getObject("id", UUID.class),
                rs.getObject("org_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("agent_id"),
                rs.getString("session_id"),
                rs.getString("content_json"),
                rs.getString("metadata_json"));
    }

    private boolean claim(UUID id) {
        int updated =
                jdbc.update(
                        """
                        UPDATE memory_events
                           SET sync_status = ?, last_error = NULL, updated_at = ?
                         WHERE id = ?
                           AND sync_status <> ?
                        """,
                        STATUS_SYNCING,
                        OffsetDateTime.now(),
                        id,
                        STATUS_SYNCED);
        return updated == 1;
    }

    private void markSynced(UUID id) {
        jdbc.update(
                """
                UPDATE memory_events
                   SET sync_status = ?,
                       sync_attempts = sync_attempts + 1,
                       synced_at = ?,
                       last_error = NULL,
                       updated_at = ?
                 WHERE id = ?
                """,
                STATUS_SYNCED,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                id);
    }

    private void markFailed(UUID id, Throwable error) {
        jdbc.update(
                """
                UPDATE memory_events
                   SET sync_status = ?,
                       sync_attempts = sync_attempts + 1,
                       last_error = ?,
                       updated_at = ?
                 WHERE id = ?
                """,
                STATUS_FAILED,
                truncate(errorMessage(error)),
                OffsetDateTime.now(),
                id);
    }

    Mem0AddRequest toAddRequest(Candidate candidate) {
        return Mem0AddRequest.builder()
                .messages(parseMessages(candidate.contentJson()))
                .agentId(candidate.agentId())
                .userId(candidate.userId().toString())
                .runId(candidate.sessionId())
                .metadata(parseMetadata(candidate))
                .infer(true)
                .build();
    }

    private List<Mem0Message> parseMessages(String contentJson) {
        try {
            JsonNode root = objectMapper.readTree(contentJson);
            JsonNode messages = root.path("messages");
            if (!messages.isArray()) {
                return List.of();
            }
            List<Mem0Message> out = new ArrayList<>();
            for (JsonNode item : messages) {
                String role = text(item, "role");
                String content = text(item, "content");
                if (role == null || content == null || content.isBlank()) {
                    continue;
                }
                out.add(
                        Mem0Message.builder()
                                .role(role)
                                .content(content)
                                .name(text(item, "name"))
                                .build());
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Invalid memory event content_json", e);
        }
    }

    private Map<String, Object> parseMetadata(Candidate candidate) {
        try {
            if (candidate.metadataJson() == null || candidate.metadataJson().isBlank()) {
                return Map.of("org_id", candidate.orgId().toString());
            }
            Map<String, Object> metadata =
                    objectMapper.readValue(
                            candidate.metadataJson(), new TypeReference<Map<String, Object>>() {});
            metadata.putIfAbsent("org_id", candidate.orgId().toString());
            metadata.putIfAbsent("agent_id", candidate.agentId());
            if (candidate.sessionId() != null) {
                metadata.putIfAbsent("session_id", candidate.sessionId());
            }
            return metadata;
        } catch (Exception e) {
            throw new IllegalStateException("Invalid memory event metadata_json", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Mem0Client buildClient(SaasProperties.Ltm ltm) {
        if (ltm == null
                || !ltm.isEnabled()
                || ltm.getMem0BaseUrl() == null
                || ltm.getMem0BaseUrl().isBlank()) {
            return null;
        }
        return SaasLongTermMemoryMiddleware.createClient(
                ltm.getMem0BaseUrl(),
                ltm.getMem0ApiKey(),
                ltm.getMem0ApiType(),
                ltm.getTimeoutSeconds());
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

    record Candidate(
            UUID id,
            UUID orgId,
            UUID userId,
            String agentId,
            String sessionId,
            String contentJson,
            String metadataJson) {}
}
