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
import io.agentscope.saas.app.support.MyBatisRepositoryTestSupport;
import io.agentscope.saas.app.support.TestDatabaseMapper;
import io.agentscope.saas.dal.mybatis.admin.OrchestrationOutboxMapper;
import io.agentscope.saas.dal.mybatis.type.UuidTypeHandler;
import io.agentscope.saas.dal.repository.MyBatisOrchestrationOutboxRepository;
import io.agentscope.saas.orchestration.OrchestrationEventDispatcher;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrchestrationOutboxPublisherTest {

    private TestDatabaseMapper database;
    private SaasProperties properties;
    private OrchestrationEventDispatcher dispatcher;
    private OrchestrationOutboxPublisher publisher;
    private SqlSession sqlSession;

    @BeforeEach
    void setUp() {
        DataSource dataSource = dataSource();
        database = MyBatisRepositoryTestSupport.mapper(dataSource, TestDatabaseMapper.class);
        database.createOutbox();
        properties = new SaasProperties();
        properties.getOrchestration().setOutboxBatchSize(10);
        properties.getOrchestration().setOutboxLeaseSeconds(30);
        properties.getOrchestration().setOutboxMaxAttempts(3);
        properties.getOrchestration().setOutboxRetryBaseSeconds(2);
        properties.getOrchestration().setOutboxRetryMaxSeconds(30);
        dispatcher = mock(OrchestrationEventDispatcher.class);
        Configuration configuration =
                new Configuration(
                        new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
        configuration.addMapper(OrchestrationOutboxMapper.class);
        sqlSession = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        var repository =
                new MyBatisOrchestrationOutboxRepository(
                        sqlSession.getMapper(OrchestrationOutboxMapper.class));
        publisher =
                new OrchestrationOutboxPublisher(repository, properties, dispatcher, "test-worker");
    }

    @AfterEach
    void tearDown() {
        sqlSession.close();
    }

    @Test
    void publishesAndAcknowledgesClaimedEvent() {
        UUID id = insertEvent(0, null, null);

        var summary = publisher.publishBatch();

        assertThat(summary.published()).isEqualTo(1);
        assertThat(summary.failed()).isZero();
        var state = database.outboxState(id);
        assertThat(state.attempts()).isEqualTo(1);
        assertThat(state.publishedAt()).isNotNull();
        assertThat(state.lockedBy()).isNull();
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
        var state = database.outboxState(id);
        assertThat(state.attempts()).isEqualTo(1);
        assertThat(state.nextAttemptAt()).isAfter(OffsetDateTime.now());
        assertThat(state.deadLetteredAt()).isNull();
        assertThat(state.lastError()).isEqualTo("event bus unavailable");
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
        assertThat(database.outboxState(expired).publishedAt()).isNotNull();
        assertThat(database.outboxState(active).publishedAt()).isNull();
        assertThat(database.outboxState(active).attempts()).isZero();
    }

    @Test
    void finalFailureMovesEventToDeadLetterState() {
        UUID id = insertEvent(2, null, null);
        doThrow(new IllegalStateException("permanent failure")).when(dispatcher).dispatch(any());

        var summary = publisher.publishBatch();

        assertThat(summary.deadLettered()).isEqualTo(1);
        var state = database.outboxState(id);
        assertThat(state.attempts()).isEqualTo(3);
        assertThat(state.nextAttemptAt()).isNull();
        assertThat(state.deadLetteredAt()).isNotNull();
    }

    private UUID insertEvent(int attempts, String lockedBy, OffsetDateTime lockedUntil) {
        UUID id = UUID.randomUUID();
        database.insertOutboxEvent(
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                OffsetDateTime.now(),
                attempts,
                lockedBy,
                lockedUntil);
        return id;
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
