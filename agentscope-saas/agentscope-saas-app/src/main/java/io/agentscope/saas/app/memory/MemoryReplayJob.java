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
import io.agentscope.saas.domain.memory.MemoryProjectionEvent;
import io.agentscope.saas.domain.memory.MemoryProjectionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

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

    private static final int MAX_ERROR_LENGTH = 2000;

    private final MemoryProjectionRepository repository;
    private final ObjectMapper objectMapper;
    private final SaasProperties properties;
    private final Mem0Client mem0Client;
    private final Clock clock;

    @Autowired
    public MemoryReplayJob(
            MemoryProjectionRepository repository,
            ObjectMapper objectMapper,
            SaasProperties properties) {
        this(repository, objectMapper, properties, buildClient(properties.getLtm()));
    }

    MemoryReplayJob(
            MemoryProjectionRepository repository,
            ObjectMapper objectMapper,
            SaasProperties properties,
            Mem0Client mem0Client) {
        this(repository, objectMapper, properties, mem0Client, Clock.systemUTC());
    }

    MemoryReplayJob(
            MemoryProjectionRepository repository,
            ObjectMapper objectMapper,
            SaasProperties properties,
            Mem0Client mem0Client,
            Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.mem0Client = mem0Client;
        this.clock = clock;
        validateConfiguration(properties.getLtm());
    }

    @Scheduled(
            fixedDelayString = "${saas.ltm.replay-fixed-delay-seconds:60}000",
            scheduler = "memoryProjectionScheduler")
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
            log.warn("Memory replay scan failed: {}", e.getClass().getSimpleName());
        }
    }

    int replayBatch() {
        SaasProperties.Ltm ltm = properties.getLtm();
        validateConfiguration(ltm);
        int maxAttempts = ltm.getReplayMaxAttempts();
        OffsetDateTime scanStarted = OffsetDateTime.now(clock);
        List<MemoryProjectionEvent> candidates =
                repository.findReplayable(
                        ltm.getReplayBatchSize(),
                        scanStarted,
                        scanStarted.minusSeconds(ltm.getReplayStaleSeconds()));
        int replayed = 0;
        for (MemoryProjectionEvent candidate : candidates) {
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (!now.isBefore(scanStarted.plusSeconds(ltm.getReplayScanBudgetSeconds()))) break;
            OffsetDateTime staleBefore = now.minusSeconds(ltm.getReplayStaleSeconds());
            UUID token = UUID.randomUUID();
            try {
                if (candidate.attempts() >= maxAttempts) {
                    repository.exhaust(
                            candidate.orgId(), candidate.id(), maxAttempts, now, staleBefore);
                    continue;
                }
                if (!repository.claim(
                        candidate,
                        token,
                        maxAttempts,
                        now,
                        now.plusSeconds(ltm.getReplayStaleSeconds()),
                        staleBefore)) continue;
                mem0Client
                        .add(toAddRequest(candidate))
                        .switchIfEmpty(Mono.error(new IllegalStateException("MEM0_EMPTY_RESPONSE")))
                        .doOnNext(
                                response -> {
                                    if (response.getResults() == null) {
                                        throw new IllegalStateException("MEM0_INCOMPLETE_RESPONSE");
                                    }
                                })
                        .timeout(Duration.ofSeconds(ltm.getTimeoutSeconds()))
                        .block();
                if (repository.markSynced(
                        candidate.orgId(), candidate.id(), token, OffsetDateTime.now(clock)))
                    replayed++;
            } catch (Exception e) {
                OffsetDateTime failedAt = OffsetDateTime.now(clock);
                try {
                    repository.markFailed(
                            candidate.orgId(),
                            candidate.id(),
                            token,
                            errorCode(e),
                            maxAttempts,
                            failedAt,
                            failedAt.plusSeconds(retryDelaySeconds(candidate.attempts() + 1)));
                } catch (RuntimeException settlementError) {
                    // A failed receipt remains leased and is reclaimed after expiry.
                    log.warn(
                            "Memory replay receipt failed event={}: {}",
                            candidate.id(),
                            settlementError.getClass().getSimpleName());
                }
                log.warn(
                        "Memory replay failed event={} org={} attempt={}: {}",
                        candidate.id(),
                        candidate.orgId(),
                        candidate.attempts() + 1,
                        errorCode(e));
            }
        }
        return replayed;
    }

    Mem0AddRequest toAddRequest(MemoryProjectionEvent candidate) {
        List<Mem0Message> messages = parseMessages(candidate.contentJson());
        if (messages.isEmpty()) throw new IllegalStateException("MEM0_EMPTY_SOURCE");
        return Mem0AddRequest.builder()
                .messages(messages)
                .agentId(candidate.agentId())
                .userId(candidate.userId().toString())
                .runId(candidate.sessionId())
                .metadata(parseMetadata(candidate))
                .infer(true)
                .asyncMode(false)
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

    private Map<String, Object> parseMetadata(MemoryProjectionEvent candidate) {
        try {
            Map<String, Object> metadata =
                    candidate.metadataJson() == null || candidate.metadataJson().isBlank()
                            ? new java.util.LinkedHashMap<>()
                            : objectMapper.readValue(
                                    candidate.metadataJson(),
                                    new TypeReference<Map<String, Object>>() {});
            metadata.put("org_id", candidate.orgId().toString());
            metadata.put("user_id", candidate.userId().toString());
            metadata.put("agent_id", candidate.agentId());
            metadata.put("memory_event_id", candidate.id().toString());
            if (candidate.sessionId() != null) {
                metadata.put("session_id", candidate.sessionId());
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

    private static String errorCode(Throwable error) {
        // Provider exceptions may contain credentials or private conversation text.
        return truncate("MEMORY_PROJECTION_" + error.getClass().getSimpleName());
    }

    long retryDelaySeconds(int attempt) {
        var config = properties.getLtm();
        long maximum = config.getReplayRetryMaxSeconds();
        long delay = config.getReplayRetryBaseSeconds();
        for (int i = 1; i < attempt && delay < maximum; i++) {
            delay = delay > maximum / 2 ? maximum : Math.min(maximum, delay * 2);
        }
        long jitter = Math.min(delay / 2, maximum - delay);
        return delay + ThreadLocalRandom.current().nextLong(jitter + 1);
    }

    static void validateConfiguration(SaasProperties.Ltm ltm) {
        if (ltm.getTimeoutSeconds() < 1
                || ltm.getTimeoutSeconds() > 3600
                || ltm.getReplayStaleSeconds() <= ltm.getTimeoutSeconds()
                || ltm.getReplayStaleSeconds() > 86400
                || ltm.getReplayBatchSize() < 1
                || ltm.getReplayBatchSize() > 1000
                || ltm.getReplayMaxAttempts() < 1
                || ltm.getReplayMaxAttempts() > 1000
                || ltm.getReplayFixedDelaySeconds() < 1
                || ltm.getReplayFixedDelaySeconds() > 86400
                || ltm.getReplayRetryBaseSeconds() < 1
                || ltm.getReplayRetryMaxSeconds() < ltm.getReplayRetryBaseSeconds()
                || ltm.getReplayRetryMaxSeconds() > 86400
                || ltm.getReplayScanBudgetSeconds() < 1
                || ltm.getReplayScanBudgetSeconds() > 3600) {
            throw new IllegalArgumentException("Invalid memory replay limits or lease <= timeout");
        }
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_ERROR_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_LENGTH);
    }
}
