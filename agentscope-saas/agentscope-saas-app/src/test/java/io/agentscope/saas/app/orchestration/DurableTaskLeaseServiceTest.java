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

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.support.MyBatisRepositoryTestSupport;
import io.agentscope.saas.app.support.TestDatabaseMapper;
import io.agentscope.saas.dal.mybatis.admin.DurableTaskLeaseMapper;
import io.agentscope.saas.dal.mybatis.type.UuidTypeHandler;
import io.agentscope.saas.dal.repository.MyBatisDurableTaskLeaseRepository;
import io.agentscope.saas.domain.orchestration.WorkspaceIsolationMode;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class DurableTaskLeaseServiceTest {

    private TestDatabaseMapper database;
    private DurableTaskLeaseService leases;
    private UUID runId;
    private UUID taskId;

    @BeforeEach
    void setUp() {
        DataSource dataSource = dataSource();
        database = MyBatisRepositoryTestSupport.mapper(dataSource, TestDatabaseMapper.class);
        database.createDurableTaskLeaseSchema();
        SaasProperties properties = new SaasProperties();
        properties.getOrchestration().setSchedulerBatchSize(10);
        properties.getOrchestration().setSchedulerLeaseSeconds(60);
        properties.getOrchestration().setSchedulerRetryMaxSeconds(30);
        leases =
                new DurableTaskLeaseService(
                        leaseRepository(dataSource),
                        new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                        properties);
        runId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        database.insertStandardTier();
        database.insertStandardUser(userId);
        database.insertMinimalRun(
                runId, orgId, userId, UUID.randomUUID(), UUID.randomUUID(), OffsetDateTime.now());
        insertTask(taskId, orgId, "IDEMPOTENT", 3);
    }

    @Test
    void onlyOneWorkerCanClaimReadyTask() {
        var first = leases.claimReady("worker-a", 1);
        var second = leases.claimReady("worker-b", 1);

        assertThat(first).hasSize(1);
        assertThat(first.get(0).attemptNo()).isEqualTo(1);
        assertThat(first.get(0).workspaceIsolationMode())
                .isEqualTo(WorkspaceIsolationMode.ATTEMPT_ISOLATED);
        assertThat(second).isEmpty();
        assertThat(taskStatus(taskId)).isEqualTo("CLAIMED");
        assertThat(attemptStatus(first.get(0).attemptId())).isEqualTo("LEASED");
    }

    @Test
    void startsHeartbeatsAndCompletesLease() {
        var lease = leases.claimReady("worker-a", 1).get(0);

        assertThat(leases.start(lease.attemptId(), "worker-a")).isTrue();
        assertThat(leases.heartbeat(lease.attemptId(), "worker-a")).isTrue();
        assertThat(leases.heartbeat(lease.attemptId(), "worker-b")).isFalse();
        assertThat(leases.succeed(lease.attemptId(), "worker-a")).isTrue();

        assertThat(taskStatus(taskId)).isEqualTo("SUCCEEDED");
        assertThat(attemptStatus(lease.attemptId())).isEqualTo("SUCCEEDED");
        assertThat(eventTypes())
                .containsExactly(
                        "TASK_CLAIMED",
                        "TASK_STARTED",
                        "ATTEMPT_SUCCEEDED",
                        "TASK_SUCCEEDED",
                        "RUN_SUCCEEDED");
    }

    @Test
    void retryableFailureCreatesANewAttemptAfterBackoff() {
        var first = leases.claimReady("worker-a", 1).get(0);
        assertThat(leases.fail(first.attemptId(), "worker-a", "TEMPORARY", "try again")).isTrue();

        assertThat(taskStatus(taskId)).isEqualTo("READY");
        assertThat(leases.claimReady("worker-b", 1)).isEmpty();
        database.updateTaskNextAttempt(taskId, OffsetDateTime.now().minusSeconds(1));

        var second = leases.claimReady("worker-b", 1).get(0);
        assertThat(second.attemptNo()).isEqualTo(2);
        assertThat(second.attemptId()).isNotEqualTo(first.attemptId());
        assertThat(attemptStatus(first.attemptId())).isEqualTo("FAILED");
    }

    @Test
    void unsafeFailureRequiresManualActionInsteadOfRetry() {
        database.updateTaskRetryMode(taskId, "MANUAL");
        var lease = leases.claimReady("worker-a", 1).get(0);

        assertThat(leases.fail(lease.attemptId(), "worker-a", "SIDE_EFFECT", "unknown outcome"))
                .isTrue();

        assertThat(taskStatus(taskId)).isEqualTo("MANUAL_ACTION");
        assertThat(leases.claimReady("worker-b", 1)).isEmpty();
    }

    @Test
    void expiredAttemptIsAbandonedAndTaskBecomesRetryable() {
        var lease = leases.claimReady("crashed-worker", 1).get(0);
        database.updateAttemptExpiry(lease.attemptId(), OffsetDateTime.now().minusSeconds(1));

        assertThat(leases.recoverExpired(10)).isEqualTo(1);

        assertThat(attemptStatus(lease.attemptId())).isEqualTo("ABANDONED");
        assertThat(taskStatus(taskId)).isEqualTo("READY");
    }

    private void insertTask(UUID id, UUID orgId, String retryMode, int maxAttempts) {
        database.insertLeaseTask(id, orgId, runId, retryMode, maxAttempts, OffsetDateTime.now());
    }

    private String taskStatus(UUID id) {
        return database.taskStatus(id);
    }

    private String attemptStatus(UUID id) {
        return database.attemptStatus(id);
    }

    private java.util.List<String> eventTypes() {
        return database.allEventTypes();
    }

    private static DataSource dataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:mem:durable-task-"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return ds;
    }

    private static MyBatisDurableTaskLeaseRepository leaseRepository(DataSource dataSource) {
        try {
            org.apache.ibatis.session.Configuration configuration =
                    new org.apache.ibatis.session.Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setArgNameBasedConstructorAutoMapping(true);
            configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
            configuration.addMapper(DurableTaskLeaseMapper.class);
            SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            factoryBean.setConfiguration(configuration);
            SqlSessionFactory factory = factoryBean.getObject();
            DurableTaskLeaseMapper mapper =
                    new SqlSessionTemplate(factory).getMapper(DurableTaskLeaseMapper.class);
            return new MyBatisDurableTaskLeaseRepository(mapper);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create lease test repository", e);
        }
    }
}
