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
import io.agentscope.saas.app.support.MyBatisRepositoryTestSupport;
import io.agentscope.saas.app.support.TestDatabaseMapper;
import io.agentscope.saas.dal.mybatis.admin.MemoryProjectionMapper;
import io.agentscope.saas.dal.mybatis.type.UuidTypeHandler;
import io.agentscope.saas.dal.repository.MyBatisMemoryProjectionRepository;
import io.agentscope.saas.domain.memory.MemoryProjectionEvent;
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
import reactor.core.publisher.Mono;

class MemoryReplayJobTest {

    private TestDatabaseMapper database;
    private Mem0Client mem0;
    private MemoryReplayJob job;
    private SqlSession sqlSession;

    @BeforeEach
    void setUp() {
        DataSource dataSource = dataSource();
        database = MyBatisRepositoryTestSupport.mapper(dataSource, TestDatabaseMapper.class);
        database.createMemoryEvents();
        mem0 = mock(Mem0Client.class);
        SaasProperties properties = new SaasProperties();
        properties.getLtm().setEnabled(true);
        properties.getLtm().setReplayBatchSize(10);
        properties.getLtm().setReplayMaxAttempts(3);
        properties.getLtm().setReplayStaleSeconds(60);
        Configuration configuration =
                new Configuration(
                        new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
        configuration.addMapper(MemoryProjectionMapper.class);
        sqlSession = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        var repository =
                new MyBatisMemoryProjectionRepository(
                        sqlSession.getMapper(MemoryProjectionMapper.class));
        job = new MemoryReplayJob(repository, new ObjectMapper(), properties, mem0);
    }

    @AfterEach
    void tearDown() {
        sqlSession.close();
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
}
