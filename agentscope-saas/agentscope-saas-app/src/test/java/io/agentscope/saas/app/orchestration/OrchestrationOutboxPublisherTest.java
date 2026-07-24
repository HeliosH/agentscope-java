/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.app.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.orchestration.OrchestrationEventDispatcher;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class OrchestrationOutboxPublisherTest {

    private JdbcTemplate jdbc;
    private SaasProperties properties;
    private OrchestrationEventDispatcher dispatcher;
    private OrchestrationOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource());
        jdbc.execute(
                """
                CREATE TABLE orchestration_outbox (
                    id UUID PRIMARY KEY,
                    org_id UUID NOT NULL,
                    aggregate_id UUID NOT NULL,
                    aggregate_type VARCHAR(64) NOT NULL,
                    event_type VARCHAR(64) NOT NULL,
                    payload_json VARCHAR(4000) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    published_at TIMESTAMP WITH TIME ZONE,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    last_error VARCHAR(2000),
                    locked_by VARCHAR(255),
                    locked_until TIMESTAMP WITH TIME ZONE,
                    next_attempt_at TIMESTAMP WITH TIME ZONE,
                    dead_lettered_at TIMESTAMP WITH TIME ZONE
                )
                """);
        properties = new SaasProperties();
        properties.getOrchestration().setOutboxBatchSize(10);
        properties.getOrchestration().setOutboxLeaseSeconds(30);
        properties.getOrchestration().setOutboxMaxAttempts(3);
        properties.getOrchestration().setOutboxRetryBaseSeconds(2);
        properties.getOrchestration().setOutboxRetryMaxSeconds(30);
        dispatcher = mock(OrchestrationEventDispatcher.class);
        publisher = new OrchestrationOutboxPublisher(jdbc, properties, dispatcher, "test-worker");
    }

    @Test
    void publishesAndAcknowledgesClaimedEvent() {
        UUID id = insertEvent(0, null, null);

        var summary = publisher.publishBatch();

        assertThat(summary.published()).isEqualTo(1);
        assertThat(summary.failed()).isZero();
        assertThat(value(id, "attempts", Integer.class)).isEqualTo(1);
        assertThat(value(id, "published_at", OffsetDateTime.class)).isNotNull();
        assertThat(value(id, "locked_by", String.class)).isNull();
        verify(dispatcher).dispatch(any(OrchestrationEventDispatcher.OutboxEvent.class));
    }

    @Test
    void failedDeliveryIsReleasedWithExponentialBackoff() {
        UUID id = insertEvent(0, null, null);
        doThrow(new IllegalStateException("event bus unavailable"))
                .when(dispatcher)
                .dispatch(any());

        var summary = publisher.publishBatch();

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.deadLettered()).isZero();
        assertThat(value(id, "attempts", Integer.class)).isEqualTo(1);
        assertThat(value(id, "next_attempt_at", OffsetDateTime.class))
                .isAfter(OffsetDateTime.now());
        assertThat(value(id, "dead_lettered_at", OffsetDateTime.class)).isNull();
        assertThat(value(id, "last_error", String.class)).isEqualTo("event bus unavailable");
        assertThat(publisher.retryDelaySeconds(1)).isEqualTo(2);
        assertThat(publisher.retryDelaySeconds(2)).isEqualTo(4);
        assertThat(publisher.retryDelaySeconds(20)).isEqualTo(30);
    }

    @Test
    void expiredLeaseCanBeReclaimedButActiveLeaseCannot() {
        UUID expired = insertEvent(0, "crashed-worker", OffsetDateTime.now().minusSeconds(1));
        UUID active = insertEvent(0, "active-worker", OffsetDateTime.now().plusMinutes(1));

        var summary = publisher.publishBatch();

        assertThat(summary.published()).isEqualTo(1);
        assertThat(value(expired, "published_at", OffsetDateTime.class)).isNotNull();
        assertThat(value(active, "published_at", OffsetDateTime.class)).isNull();
        assertThat(value(active, "attempts", Integer.class)).isZero();
    }

    @Test
    void finalFailureMovesEventToDeadLetterState() {
        UUID id = insertEvent(2, null, null);
        doThrow(new IllegalStateException("permanent failure")).when(dispatcher).dispatch(any());

        var summary = publisher.publishBatch();

        assertThat(summary.deadLettered()).isEqualTo(1);
        assertThat(value(id, "attempts", Integer.class)).isEqualTo(3);
        assertThat(value(id, "next_attempt_at", OffsetDateTime.class)).isNull();
        assertThat(value(id, "dead_lettered_at", OffsetDateTime.class)).isNotNull();
    }

    private UUID insertEvent(int attempts, String lockedBy, OffsetDateTime lockedUntil) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO orchestration_outbox
                    (id, org_id, aggregate_id, aggregate_type, event_type, payload_json,
                     created_at, attempts, locked_by, locked_until)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "assistant_run",
                "RUN_STARTED",
                "{}",
                OffsetDateTime.now(),
                attempts,
                lockedBy,
                lockedUntil);
        return id;
    }

    private <T> T value(UUID id, String column, Class<T> type) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM orchestration_outbox WHERE id = ?", type, id);
    }

    private static DataSource dataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:mem:orchestration-outbox-"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return ds;
    }
}
