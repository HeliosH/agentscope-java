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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.memory.mem0.Mem0AddRequest;
import io.agentscope.core.memory.mem0.Mem0AddResponse;
import io.agentscope.core.memory.mem0.Mem0ApiType;
import io.agentscope.core.memory.mem0.Mem0Client;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.support.MyBatisRepositoryTestSupport;
import io.agentscope.saas.app.support.TestDatabaseMapper;
import io.agentscope.saas.dal.mybatis.admin.MemoryProjectionMapper;
import io.agentscope.saas.dal.repository.MyBatisMemoryProjectionRepository;
import io.agentscope.saas.domain.memory.MemoryProjectionEvent;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class MemoryReplayJobTest {

    private TestDatabaseMapper database;
    private Mem0Client mem0;
    private MemoryReplayJob job;
    private MyBatisMemoryProjectionRepository repository;
    private SaasProperties properties;
    private final MutableClock clock = new MutableClock();

    @BeforeEach
    void setUp() {
        DataSource dataSource = dataSource();
        database = MyBatisRepositoryTestSupport.mapper(dataSource, TestDatabaseMapper.class);
        database.createMemoryEvents();
        mem0 = mock(Mem0Client.class);
        properties = new SaasProperties();
        properties.getLtm().setEnabled(true);
        properties.getLtm().setReplayBatchSize(10);
        properties.getLtm().setReplayMaxAttempts(3);
        properties.getLtm().setReplayStaleSeconds(60);
        properties.getLtm().setTimeoutSeconds(1);
        repository =
                new MyBatisMemoryProjectionRepository(
                        MyBatisRepositoryTestSupport.mapper(
                                dataSource, MemoryProjectionMapper.class));
        job = new MemoryReplayJob(repository, new ObjectMapper(), properties, mem0, clock);
    }

    @Test
    void replaysPendingEventAndMarksSynced() {
        UUID id = insertEvent("pending", 0, null);
        when(mem0.add(any())).thenReturn(Mono.just(completedResponse()));

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
        assertThat(lastError(id)).isEqualTo("MEMORY_PROJECTION_RuntimeException");
        assertThat(job.replayBatch()).isZero();
        verify(mem0, times(1)).add(any());
    }

    @Test
    void buildsMem0RequestFromLedgerPayload() {
        UUID id = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        MemoryProjectionEvent candidate =
                new MemoryProjectionEvent(
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
                                .formatted(orgId),
                        0);

        Mem0AddRequest request = job.toAddRequest(candidate);

        assertThat(request.getUserId()).isEqualTo(userId.toString());
        assertThat(request.getAgentId()).isEqualTo("assistant");
        assertThat(request.getRunId()).isEqualTo("session-1");
        assertThat(request.getMetadata()).containsEntry("org_id", orgId.toString());
        assertThat(request.getMetadata()).containsEntry("memory_event_id", id.toString());
        assertThat(request.getAsyncMode()).isFalse();
        assertThat(request.getMessages()).hasSize(1);
        assertThat(request.getMessages().get(0).getRole()).isEqualTo("user");
        assertThat(request.getMessages().get(0).getContent()).isEqualTo("remember tea");
        assertThat(request.getMessages().get(0).getName()).isEqualTo("alice");
    }

    @Test
    void replaysAfterBackoffAndUsesPersistedAttemptCount() {
        UUID id = insertEvent("pending", 0, null);
        when(mem0.add(any())).thenReturn(Mono.error(new IllegalStateException("down")));
        assertThat(job.replayBatch()).isZero();
        assertThat(job.replayBatch()).isZero();
        clock.advance(3600);
        when(mem0.add(any())).thenReturn(Mono.just(completedResponse()));
        assertThat(job.replayBatch()).isEqualTo(1);
        assertThat(attempts(id)).isEqualTo(2);
        assertThat(status(id)).isEqualTo("synced");
        verify(mem0, times(2)).add(any());
    }

    @Test
    void lateWorkerCannotSettleReclaimedLeaseOrAnotherOrganization() {
        insertEvent("pending", 0, null);
        var candidate = repository.findReplayable(10, now(), now().minusSeconds(60)).get(0);
        UUID oldToken = UUID.randomUUID();
        assertThat(
                        repository.claim(
                                candidate,
                                oldToken,
                                3,
                                now(),
                                now().plusSeconds(60),
                                now().minusSeconds(60)))
                .isTrue();
        clock.advance(61);
        assertThat(repository.markSynced(candidate.orgId(), candidate.id(), oldToken, now()))
                .isFalse();
        var reclaimed = repository.findReplayable(10, now(), now().minusSeconds(60)).get(0);
        UUID newToken = UUID.randomUUID();
        assertThat(
                        repository.claim(
                                reclaimed,
                                newToken,
                                3,
                                now(),
                                now().plusSeconds(60),
                                now().minusSeconds(60)))
                .isTrue();
        assertThat(
                        repository.markFailed(
                                candidate.orgId(),
                                candidate.id(),
                                oldToken,
                                "old failure",
                                3,
                                now(),
                                now().plusSeconds(60)))
                .isFalse();
        assertThat(repository.markSynced(UUID.randomUUID(), candidate.id(), newToken, now()))
                .isFalse();
        assertThat(repository.markSynced(candidate.orgId(), candidate.id(), newToken, now()))
                .isTrue();
        assertThat(attempts(candidate.id())).isEqualTo(2);
    }

    @Test
    void concurrentClaimsAdmitOneWorker() throws Exception {
        insertEvent("pending", 0, null);
        var candidate = repository.findReplayable(10, now(), now().minusSeconds(60)).get(0);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first =
                    pool.submit(
                            () -> {
                                start.await();
                                return repository.claim(
                                        candidate,
                                        UUID.randomUUID(),
                                        3,
                                        now(),
                                        now().plusSeconds(60),
                                        now().minusSeconds(60));
                            });
            var second =
                    pool.submit(
                            () -> {
                                start.await();
                                return repository.claim(
                                        candidate,
                                        UUID.randomUUID(),
                                        3,
                                        now(),
                                        now().plusSeconds(60),
                                        now().minusSeconds(60));
                            });
            start.countDown();
            assertThat(
                            (first.get(5, TimeUnit.SECONDS) ? 1 : 0)
                                    + (second.get(5, TimeUnit.SECONDS) ? 1 : 0))
                    .isEqualTo(1);
            assertThat(attempts(candidate.id())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void lastAttemptFailureBecomesDeadLetterWithoutRepeatedCalls() {
        UUID id = insertEvent("failed", 2, null);
        when(mem0.add(any())).thenReturn(Mono.error(new IllegalStateException("down")));
        assertThat(job.replayBatch()).isZero();
        assertThat(status(id)).isEqualTo("dead_letter");
        assertThat(attempts(id)).isEqualTo(3);
        clock.advance(3600);
        assertThat(job.replayBatch()).isZero();
        verify(mem0, times(1)).add(any());
    }

    @Test
    void crashedLastAttemptBecomesDeadLetterAfterLeaseExpiry() {
        UUID id = insertEvent("pending", 2, null);
        var candidate = repository.findReplayable(10, now(), now().minusSeconds(60)).get(0);
        assertThat(
                        repository.claim(
                                candidate,
                                UUID.randomUUID(),
                                3,
                                now(),
                                now().plusSeconds(60),
                                now().minusSeconds(60)))
                .isTrue();
        clock.advance(61);
        assertThat(job.replayBatch()).isZero();
        assertThat(status(id)).isEqualTo("dead_letter");
        assertThat(attempts(id)).isEqualTo(3);
        verify(mem0, never()).add(any());
    }

    @Test
    void legacySyncingRowsCanBeReclaimedWithoutResettingAttempts() {
        UUID id = insertEvent("syncing", 1, null);
        clock.advance(61);
        when(mem0.add(any())).thenReturn(Mono.just(completedResponse()));
        assertThat(job.replayBatch()).isEqualTo(1);
        assertThat(attempts(id)).isEqualTo(2);
    }

    @Test
    void hungProjectionTimesOutAndRemainsReplayable() {
        UUID id = insertEvent("pending", 0, null);
        when(mem0.add(any())).thenReturn(Mono.never());
        assertThat(job.replayBatch()).isZero();
        assertThat(status(id)).isEqualTo("failed");
        assertThat(attempts(id)).isEqualTo(1);
    }

    @Test
    void emptyProviderCompletionDoesNotCountAsSynced() {
        UUID id = insertEvent("pending", 0, null);
        when(mem0.add(any())).thenReturn(Mono.empty());
        assertThat(job.replayBatch()).isZero();
        assertThat(status(id)).isEqualTo("failed");
    }

    @Test
    void metadataCannotOverrideDurableOwner() {
        UUID organization = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        var event =
                new MemoryProjectionEvent(
                        UUID.randomUUID(),
                        organization,
                        user,
                        "assistant",
                        "session",
                        "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
                        "{\"org_id\":\"other\",\"user_id\":\"other\",\"agent_id\":\"other\"}",
                        0);
        var metadata = job.toAddRequest(event).getMetadata();
        assertThat(metadata)
                .containsEntry("org_id", organization.toString())
                .containsEntry("user_id", user.toString())
                .containsEntry("agent_id", "assistant");
    }

    @Test
    void rejectsLeaseThatCanExpireDuringConfiguredRequest() {
        properties.getLtm().setReplayStaleSeconds(1);
        assertThatThrownBy(() -> MemoryReplayJob.validateConfiguration(properties.getLtm()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void realSdkHttpFailureThenRecoveryPreservesDurableSourceIdentity() throws Exception {
        UUID id = insertEvent("pending", 0, null);
        var calls = new AtomicInteger();
        var request = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/memories",
                exchange -> {
                    request.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    int status = calls.incrementAndGet() == 1 ? 503 : 200;
                    byte[] body =
                            (status == 503 ? "private-provider-error" : "{\"results\":[]}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
        try {
            var client =
                    new Mem0Client(
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            null,
                            Mem0ApiType.SELF_HOSTED,
                            Duration.ofSeconds(1));
            var httpJob =
                    new MemoryReplayJob(repository, new ObjectMapper(), properties, client, clock);
            assertThat(httpJob.replayBatch()).isZero();
            assertThat(status(id)).isEqualTo("failed");
            assertThat(lastError(id)).doesNotContain("private-provider-error");
            clock.advance(3600);
            assertThat(httpJob.replayBatch()).isEqualTo(1);
            assertThat(status(id)).isEqualTo("synced");
            assertThat(attempts(id)).isEqualTo(2);
            var json = new ObjectMapper().readTree(request.get());
            assertThat(json.path("async_mode").asBoolean(true)).isFalse();
            assertThat(json.path("metadata").path("memory_event_id").asText())
                    .isEqualTo(id.toString());
            assertThat(calls.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void scanTimeBudgetDoesNotClaimTheRemainingBatch() {
        insertEvent("pending", 0, null);
        insertEvent("pending", 0, null);
        when(mem0.add(any()))
                .thenAnswer(
                        ignored -> {
                            clock.advance(31);
                            return Mono.just(completedResponse());
                        });
        assertThat(job.replayBatch()).isEqualTo(1);
        verify(mem0, times(1)).add(any());
        assertThat(repository.findReplayable(10, now(), now().minusSeconds(60))).hasSize(1);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    @Test
    void acceptedButIncompleteProviderReceiptIsNotSynced() {
        UUID id = insertEvent("pending", 0, null);
        var accepted = new Mem0AddResponse();
        accepted.setMessage("accepted for processing");
        when(mem0.add(any())).thenReturn(Mono.just(accepted));
        assertThat(job.replayBatch()).isZero();
        assertThat(status(id)).isEqualTo("failed");
    }

    private static Mem0AddResponse completedResponse() {
        var response = new Mem0AddResponse();
        response.setResults(java.util.List.of());
        return response;
    }

    private UUID insertEvent(String status, int attempts, String lastError) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = now();
        database.insertMemoryEvent(
                id, UUID.randomUUID(), UUID.randomUUID(), status, attempts, lastError, now);
        return id;
    }

    private String status(UUID id) {
        return database.memoryState(id).syncStatus();
    }

    private Integer attempts(UUID id) {
        return database.memoryState(id).syncAttempts();
    }

    private String lastError(UUID id) {
        return database.memoryState(id).lastError();
    }

    private static DataSource dataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:mem:memory-replay-"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return ds;
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant = Instant.parse("2026-10-06T04:00:00Z");

        void advance(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
