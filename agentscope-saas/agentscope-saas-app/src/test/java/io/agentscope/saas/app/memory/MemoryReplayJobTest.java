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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.memory.mem0.Mem0AddRequest;
import io.agentscope.core.memory.mem0.Mem0AddResponse;
import io.agentscope.core.memory.mem0.Mem0Client;
import io.agentscope.saas.app.config.SaasProperties;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Mono;

class MemoryReplayJobTest {

    private JdbcTemplate jdbc;
    private Mem0Client mem0;
    private MemoryReplayJob job;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource());
        jdbc.execute(
                """
                CREATE TABLE memory_events (
                    id UUID PRIMARY KEY,
                    org_id UUID NOT NULL,
                    user_id UUID NOT NULL,
                    agent_id VARCHAR(255) NOT NULL,
                    session_id VARCHAR(255),
                    source VARCHAR(64) NOT NULL,
                    event_type VARCHAR(64) NOT NULL,
                    content_json VARCHAR(4000) NOT NULL,
                    metadata_json VARCHAR(4000),
                    sync_status VARCHAR(20) NOT NULL,
                    sync_attempts INTEGER NOT NULL DEFAULT 0,
                    synced_at TIMESTAMP WITH TIME ZONE,
                    last_error VARCHAR(4000),
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        mem0 = mock(Mem0Client.class);
        SaasProperties properties = new SaasProperties();
        properties.getLtm().setEnabled(true);
        properties.getLtm().setReplayBatchSize(10);
        properties.getLtm().setReplayMaxAttempts(3);
        properties.getLtm().setReplayStaleSeconds(60);
        job = new MemoryReplayJob(jdbc, new ObjectMapper(), properties, mem0);
    }

    @Test
    void replaysPendingEventAndMarksSynced() {
        UUID id = insertEvent("pending", 0, null);
        when(mem0.add(any())).thenReturn(Mono.just(new Mem0AddResponse()));

        int replayed = job.replayBatch();

        assertThat(replayed).isEqualTo(1);
        assertThat(status(id)).isEqualTo("synced");
        assertThat(attempts(id)).isEqualTo(1);
        assertThat(lastError(id)).isNull();
        verify(mem0).add(any(Mem0AddRequest.class));
    }

    @Test
    void marksFailedWhenProjectionFails() {
        UUID id = insertEvent("failed", 1, "old error");
        when(mem0.add(any())).thenReturn(Mono.error(new RuntimeException("mem0 down")));

        int replayed = job.replayBatch();

        assertThat(replayed).isZero();
        assertThat(status(id)).isEqualTo("failed");
        assertThat(attempts(id)).isEqualTo(2);
        assertThat(lastError(id)).isEqualTo("mem0 down");
    }

    @Test
    void buildsMem0RequestFromLedgerPayload() {
        UUID id = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        MemoryReplayJob.Candidate candidate =
                new MemoryReplayJob.Candidate(
                        id,
                        orgId,
                        userId,
                        "assistant",
                        "session-1",
                        """
                        {"messages":[{"role":"user","content":"remember tea","name":"alice"}]}
                        """,
                        """
                        {"org_id":"%s","agent_id":"assistant","session_id":"session-1"}
                        """
                                .formatted(orgId));

        Mem0AddRequest request = job.toAddRequest(candidate);

        assertThat(request.getUserId()).isEqualTo(userId.toString());
        assertThat(request.getAgentId()).isEqualTo("assistant");
        assertThat(request.getRunId()).isEqualTo("session-1");
        assertThat(request.getMetadata()).containsEntry("org_id", orgId.toString());
        assertThat(request.getMessages()).hasSize(1);
        assertThat(request.getMessages().get(0).getRole()).isEqualTo("user");
        assertThat(request.getMessages().get(0).getContent()).isEqualTo("remember tea");
        assertThat(request.getMessages().get(0).getName()).isEqualTo("alice");
    }

    private UUID insertEvent(String status, int attempts, String lastError) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update(
                """
                INSERT INTO memory_events
                    (id, org_id, user_id, agent_id, session_id, source, event_type, content_json,
                     metadata_json, sync_status, sync_attempts, last_error, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "assistant",
                "session-1",
                "mem0",
                "conversation",
                "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
                "{\"org_id\":\"org-1\",\"agent_id\":\"assistant\",\"session_id\":\"session-1\"}",
                status,
                attempts,
                lastError,
                now,
                now);
        return id;
    }

    private String status(UUID id) {
        return jdbc.queryForObject(
                "SELECT sync_status FROM memory_events WHERE id = ?", String.class, id);
    }

    private Integer attempts(UUID id) {
        return jdbc.queryForObject(
                "SELECT sync_attempts FROM memory_events WHERE id = ?", Integer.class, id);
    }

    private String lastError(UUID id) {
        return jdbc.queryForObject(
                "SELECT last_error FROM memory_events WHERE id = ?", String.class, id);
    }

    private static DataSource dataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:mem:memory-replay-"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return ds;
    }
}
