/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.support;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** MyBatis-only database fixtures and assertions shared by persistence integration tests. */
public interface TestDatabaseMapper {

    @Update(
            "CREATE TABLE tier_policies (tier VARCHAR(20) PRIMARY KEY, max_sandboxes INTEGER, "
                    + "monthly_token_quota BIGINT)")
    void createTierPolicies();

    @Update("CREATE TABLE users (id UUID PRIMARY KEY, role VARCHAR(20), tier VARCHAR(20))")
    void createUsers();

    @Update(
            """
            CREATE TABLE assistant_runs (
                id UUID PRIMARY KEY, org_id UUID NOT NULL, user_id UUID NOT NULL,
                agent_id UUID NOT NULL, session_id UUID NOT NULL,
                status VARCHAR(32) NOT NULL, next_event_seq BIGINT NOT NULL,
                failure_code VARCHAR(128), failure_message VARCHAR(2000),
                completed_at TIMESTAMP WITH TIME ZONE,
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL)
            """)
    void createAssistantRuns();

    @Update(
            """
            CREATE TABLE task_nodes (
                id UUID PRIMARY KEY, org_id UUID NOT NULL, run_id UUID NOT NULL,
                parent_id UUID, owner_agent_run_id UUID, sub_session_id VARCHAR(255),
                title VARCHAR(500), input_json VARCHAR(4000), status VARCHAR(32) NOT NULL,
                priority INTEGER NOT NULL, max_attempts INTEGER NOT NULL,
                retry_mode VARCHAR(32) NOT NULL, retry_base_seconds INTEGER NOT NULL,
                next_attempt_at TIMESTAMP WITH TIME ZONE, last_error_code VARCHAR(128),
                last_error_message VARCHAR(2000), output_json JSON DEFAULT '{}',
                created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                updated_at TIMESTAMP WITH TIME ZONE, completed_at TIMESTAMP WITH TIME ZONE)
            """)
    void createTaskNodes();

    @Update("CREATE TABLE task_edges (from_task_id UUID NOT NULL, to_task_id UUID NOT NULL)")
    void createTaskEdges();

    @Update(
            """
            CREATE TABLE agent_runs (
                id UUID PRIMARY KEY, run_id UUID NOT NULL, task_id UUID NOT NULL,
                agent_type VARCHAR(128) NOT NULL, status VARCHAR(32) NOT NULL,
                updated_at TIMESTAMP WITH TIME ZONE,
                completed_at TIMESTAMP WITH TIME ZONE)
            """)
    void createAgentRuns();

    @Update(
            """
            CREATE TABLE run_attempts (
                id UUID PRIMARY KEY, org_id UUID NOT NULL, run_id UUID NOT NULL,
                task_id UUID NOT NULL, agent_run_id UUID, attempt_no INTEGER NOT NULL,
                status VARCHAR(32) NOT NULL,
                lease_owner VARCHAR(255), lease_expires_at TIMESTAMP WITH TIME ZONE,
                heartbeat_at TIMESTAMP WITH TIME ZONE, idempotency_key VARCHAR(255),
                error_code VARCHAR(128), error_message VARCHAR(2000),
                started_at TIMESTAMP WITH TIME ZONE, completed_at TIMESTAMP WITH TIME ZONE,
                updated_at TIMESTAMP WITH TIME ZONE)
            """)
    void createRunAttempts();

    @Update(
            """
            CREATE TABLE run_events (
                id UUID PRIMARY KEY, org_id UUID NOT NULL, user_id UUID NOT NULL,
                run_id UUID NOT NULL, task_id UUID, agent_run_id UUID, attempt_id UUID,
                seq BIGINT NOT NULL,
                event_type VARCHAR(64) NOT NULL, payload_json JSON,
                created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP)
            """)
    void createRunEvents();

    @Update(
            """
            CREATE TABLE orchestration_outbox (
                id UUID PRIMARY KEY, org_id UUID NOT NULL, aggregate_id UUID NOT NULL,
                aggregate_type VARCHAR(64) NOT NULL, event_type VARCHAR(64) NOT NULL,
                payload_json JSON, created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP)
            """)
    void createLeaseOutbox();

    default void createDurableTaskLeaseSchema() {
        createTierPolicies();
        createUsers();
        createAssistantRuns();
        createTaskNodes();
        createTaskEdges();
        createAgentRuns();
        createRunAttempts();
        createRunEvents();
        createLeaseOutbox();
    }

    @Insert(
            "INSERT INTO tier_policies (tier, max_sandboxes, monthly_token_quota) "
                    + "VALUES ('standard', 2, 100000)")
    int insertStandardTier();

    @Insert("INSERT INTO users (id, role, tier) VALUES (#{id}, 'member', 'standard')")
    int insertStandardUser(UUID id);

    @Insert(
            """
            INSERT INTO assistant_runs
                (id, org_id, user_id, agent_id, session_id, status, next_event_seq, updated_at)
            VALUES
                (#{runId}, #{orgId}, #{userId}, #{agentId}, #{sessionId}, 'RUNNING', 0, #{now})
            """)
    int insertMinimalRun(
            @Param("runId") UUID runId,
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("agentId") UUID agentId,
            @Param("sessionId") UUID sessionId,
            @Param("now") OffsetDateTime now);

    @Insert(
            """
            INSERT INTO task_nodes
                (id, org_id, run_id, title, input_json, status, priority, max_attempts,
                 retry_mode, retry_base_seconds, created_at, updated_at)
            VALUES
                (#{id}, #{orgId}, #{runId}, 'test task', '{}', 'READY', 0, #{maxAttempts},
                 #{retryMode}, 2, #{now}, #{now})
            """)
    int insertLeaseTask(
            @Param("id") UUID id,
            @Param("orgId") UUID orgId,
            @Param("runId") UUID runId,
            @Param("retryMode") String retryMode,
            @Param("maxAttempts") int maxAttempts,
            @Param("now") OffsetDateTime now);

    @Update("UPDATE task_nodes SET next_attempt_at = #{nextAttemptAt} WHERE id = #{id}")
    int updateTaskNextAttempt(
            @Param("id") UUID id, @Param("nextAttemptAt") OffsetDateTime nextAttemptAt);

    @Update("UPDATE task_nodes SET retry_mode = #{retryMode} WHERE id = #{id}")
    int updateTaskRetryMode(@Param("id") UUID id, @Param("retryMode") String retryMode);

    @Update("UPDATE run_attempts SET lease_expires_at = #{expiresAt} WHERE id = #{id}")
    int updateAttemptExpiry(@Param("id") UUID id, @Param("expiresAt") OffsetDateTime expiresAt);

    @Select("SELECT status FROM task_nodes WHERE id = #{id}")
    String taskStatus(UUID id);

    @Select("SELECT status FROM run_attempts WHERE id = #{id}")
    String attemptStatus(UUID id);

    @Select("SELECT event_type FROM run_events ORDER BY seq")
    List<String> allEventTypes();

    @Update(
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
                dead_lettered_at TIMESTAMP WITH TIME ZONE)
            """)
    void createOutbox();

    @Insert(
            """
            INSERT INTO orchestration_outbox
                (id, org_id, aggregate_id, aggregate_type, event_type, payload_json,
                 created_at, attempts, locked_by, locked_until)
            VALUES
                (#{id}, #{orgId}, #{aggregateId}, 'assistant_run', 'RUN_STARTED', '{}',
                 #{createdAt}, #{attempts}, #{lockedBy}, #{lockedUntil})
            """)
    int insertOutboxEvent(
            @Param("id") UUID id,
            @Param("orgId") UUID orgId,
            @Param("aggregateId") UUID aggregateId,
            @Param("createdAt") OffsetDateTime createdAt,
            @Param("attempts") int attempts,
            @Param("lockedBy") String lockedBy,
            @Param("lockedUntil") OffsetDateTime lockedUntil);

    @Select(
            """
            SELECT attempts, published_at, locked_by, next_attempt_at, dead_lettered_at, last_error
              FROM orchestration_outbox
             WHERE id = #{id}
            """)
    OutboxState outboxState(UUID id);

    @Update(
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
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL)
            """)
    void createMemoryEvents();

    @Insert(
            """
            INSERT INTO memory_events
                (id, org_id, user_id, agent_id, session_id, source, event_type, content_json,
                 metadata_json, sync_status, sync_attempts, last_error, created_at, updated_at)
            VALUES
                (#{id}, #{orgId}, #{userId}, 'assistant', 'session-1', 'mem0', 'conversation',
                 '{"messages":[{"role":"user","content":"hello"}]}',
                 '{"org_id":"org-1","agent_id":"assistant","session_id":"session-1"}',
                 #{status}, #{attempts}, #{lastError}, #{now}, #{now})
            """)
    int insertMemoryEvent(
            @Param("id") UUID id,
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("status") String status,
            @Param("attempts") int attempts,
            @Param("lastError") String lastError,
            @Param("now") OffsetDateTime now);

    @Select("SELECT sync_status, sync_attempts, last_error FROM memory_events WHERE id = #{id}")
    MemoryState memoryState(UUID id);

    @Update(
            """
            CREATE TABLE sandboxes (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                user_id UUID NOT NULL,
                sandbox_type VARCHAR(32) NOT NULL,
                external_id VARCHAR(255),
                status VARCHAR(32) NOT NULL,
                created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                last_used_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                expires_at TIMESTAMP WITH TIME ZONE,
                backend_release_status VARCHAR(32),
                backend_release_attempts INTEGER NOT NULL DEFAULT 0,
                backend_released_at TIMESTAMP WITH TIME ZONE,
                backend_release_error VARCHAR(2000))
            """)
    void createSandboxes();

    @Insert(
            """
            INSERT INTO sandboxes
                (id, org_id, user_id, sandbox_type, external_id, status,
                 created_at, last_used_at, expires_at,
                 backend_release_status, backend_release_attempts)
            VALUES
                (#{id}, #{orgId}, #{userId}, #{type}, #{externalId}, #{status},
                 #{createdAt}, #{lastUsedAt}, #{expiresAt}, #{releaseStatus}, #{attempts})
            """)
    int insertSandbox(
            @Param("id") UUID id,
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("type") String type,
            @Param("externalId") String externalId,
            @Param("status") String status,
            @Param("createdAt") OffsetDateTime createdAt,
            @Param("lastUsedAt") OffsetDateTime lastUsedAt,
            @Param("expiresAt") OffsetDateTime expiresAt,
            @Param("releaseStatus") String releaseStatus,
            @Param("attempts") int attempts);

    @Select(
            """
            SELECT status, backend_release_status, backend_release_attempts,
                   backend_released_at, backend_release_error
              FROM sandboxes
             WHERE id = #{id}
            """)
    SandboxState sandboxState(UUID id);

    @Update(
            """
            CREATE TABLE files (
                id UUID PRIMARY KEY, org_id UUID NOT NULL, user_id UUID NOT NULL,
                current_version_id UUID, status VARCHAR(32) NOT NULL,
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL)
            """)
    void createGcFiles();

    @Update(
            """
            CREATE TABLE file_versions (
                id UUID PRIMARY KEY, file_id UUID NOT NULL, org_id UUID NOT NULL,
                user_id UUID NOT NULL, version_no BIGINT NOT NULL,
                object_key VARCHAR(1024) NOT NULL, storage_backend VARCHAR(32) NOT NULL)
            """)
    void createGcFileVersions();

    @Update(
            "CREATE TABLE file_attachments (id UUID PRIMARY KEY, file_id UUID NOT NULL, "
                    + "file_version_id UUID NOT NULL)")
    void createGcFileAttachments();

    @Update(
            """
            CREATE TABLE file_object_gc_queue (
                id UUID PRIMARY KEY, org_id UUID NOT NULL, object_key VARCHAR(1024) NOT NULL,
                storage_backend VARCHAR(32) NOT NULL, status VARCHAR(32) NOT NULL,
                attempts INTEGER NOT NULL, last_error VARCHAR(2000),
                created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL)
            """)
    void createGcQueue();

    default void createFileGcSchema() {
        createGcFiles();
        createGcFileVersions();
        createGcFileAttachments();
        createGcQueue();
    }

    @Insert(
            """
            INSERT INTO files (id, org_id, user_id, current_version_id, status, updated_at)
            VALUES (#{id}, #{orgId}, #{userId}, #{currentVersionId}, #{status}, #{updatedAt})
            """)
    int insertGcFile(
            @Param("id") UUID id,
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("currentVersionId") UUID currentVersionId,
            @Param("status") String status,
            @Param("updatedAt") OffsetDateTime updatedAt);

    @Insert(
            """
            INSERT INTO file_versions
                (id, file_id, org_id, user_id, version_no, object_key, storage_backend)
            VALUES
                (#{id}, #{fileId}, #{orgId}, #{userId}, #{versionNo}, #{objectKey}, 'pg')
            """)
    int insertGcVersion(
            @Param("id") UUID id,
            @Param("fileId") UUID fileId,
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("versionNo") long versionNo,
            @Param("objectKey") String objectKey);

    @Select("SELECT COUNT(*) FROM files")
    long countFiles();

    @Select("SELECT COUNT(*) FROM file_versions")
    long countFileVersions();

    @Select("SELECT status FROM file_object_gc_queue")
    String gcQueueStatus();

    @Insert(
            """
            INSERT INTO agents (id, org_id, user_id, name, status)
            VALUES (#{id}, #{orgId}, #{userId}, #{name}, 'active')
            """)
    int insertAgent(
            @Param("id") UUID id,
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("name") String name);

    @Insert(
            """
            INSERT INTO chat_sessions (id, org_id, user_id, agent_id, title)
            VALUES (#{id}, #{orgId}, #{userId}, #{agentId}, #{title})
            """)
    int insertChatSession(
            @Param("id") UUID id,
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("agentId") UUID agentId,
            @Param("title") String title);

    @Insert(
            """
            INSERT INTO assistant_runs
                (id, org_id, user_id, agent_id, session_id, mode, status,
                 cancel_requested, next_event_seq, started_at, updated_at)
            VALUES
                (#{id}, #{orgId}, #{userId}, #{agentId}, #{sessionId}, 'PLANNED', 'RUNNING',
                 FALSE, 0, #{now}, #{now})
            """)
    int insertPlannedRun(
            @Param("id") UUID id,
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("agentId") UUID agentId,
            @Param("sessionId") UUID sessionId,
            @Param("now") OffsetDateTime now);

    @Insert(
            """
            INSERT INTO task_nodes
                (id, org_id, run_id, title, task_type, status, priority, input_json,
                 expected_output_json, acceptance_json, workspace_mode, max_attempts,
                 retry_mode, retry_base_seconds, updated_at)
            VALUES
                (#{id}, #{orgId}, #{runId}, #{title}, 'agent', 'READY', 0,
                 CAST(#{inputJson} AS JSON), CAST('{}' AS JSON), CAST('[]' AS JSON), 'NONE', 2,
                 'IDEMPOTENT', 1, #{now})
            """)
    int insertReadyTask(
            @Param("id") UUID id,
            @Param("orgId") UUID orgId,
            @Param("runId") UUID runId,
            @Param("title") String title,
            @Param("inputJson") String inputJson,
            @Param("now") OffsetDateTime now);

    @Select("SELECT status, output_json FROM task_nodes WHERE id = #{id}")
    TaskState taskState(UUID id);

    @Select("SELECT event_type FROM run_events WHERE run_id = #{runId} ORDER BY seq")
    List<String> runEventTypes(UUID runId);

    @Select(
            """
            SELECT status, session_id, consumed_tokens, consumed_cost_micros,
                   consumed_model_calls, failure_code
              FROM assistant_runs
             WHERE id = #{id}
            """)
    RunState runState(UUID id);

    @Update("UPDATE assistant_runs SET deadline_at = #{deadline} WHERE id = #{id}")
    int updateRunDeadline(@Param("id") UUID id, @Param("deadline") OffsetDateTime deadline);

    @Select("SELECT status, depth, parent_agent_run_id FROM agent_runs WHERE id = #{id}")
    AgentRunState agentRunState(UUID id);

    @Select(
            """
            SELECT owner_agent_run_id
              FROM task_nodes
             WHERE run_id = #{runId}
               AND external_task_id = #{externalTaskId}
            """)
    UUID taskOwnerAgentRun(
            @Param("runId") UUID runId, @Param("externalTaskId") String externalTaskId);

    @Select("SELECT COUNT(*) FROM run_events WHERE run_id = #{runId}")
    long countRunEvents(UUID runId);

    @Select("SELECT COUNT(*) FROM orchestration_outbox WHERE aggregate_id = #{runId}")
    long countOutboxEvents(UUID runId);

    @Select("SELECT COUNT(*) FROM chat_messages WHERE session_id = #{sessionId}")
    long countSessionMessages(UUID sessionId);

    @Select("SELECT COUNT(*) FROM chat_messages WHERE source_run_id = #{runId}")
    int countRunMessages(UUID runId);

    @Select("SELECT CURRENT_USER")
    String currentUser();

    @Select("SELECT current_setting('app.current_org', true)")
    String currentOrg();

    @Insert(
            """
            INSERT INTO sandboxes
                (id, org_id, user_id, sandbox_type, external_id, status, expires_at)
            VALUES
                (#{id}, #{orgId}, #{userId}, 'opensandbox', #{externalId}, 'active', #{expiresAt})
            """)
    int insertMaintenanceSandbox(
            @Param("id") UUID id,
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("externalId") String externalId,
            @Param("expiresAt") OffsetDateTime expiresAt);

    @Insert(
            """
            INSERT INTO files
                (id, org_id, user_id, logical_path, current_version_id, source, status)
            VALUES
                (#{id}, #{orgId}, #{userId}, #{logicalPath}, #{currentVersionId},
                 'maintenance-test', #{status})
            """)
    int insertMaintenanceFile(
            @Param("id") UUID id,
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("logicalPath") String logicalPath,
            @Param("currentVersionId") UUID currentVersionId,
            @Param("status") String status);

    @Insert(
            """
            INSERT INTO file_versions
                (id, file_id, org_id, user_id, version_no, object_key, storage_backend,
                 size_bytes, sha256, source)
            VALUES
                (#{id}, #{fileId}, #{orgId}, #{userId}, #{versionNo}, #{objectKey},
                 'pg', 1, #{sha256}, 'maintenance-test')
            """)
    int insertMaintenanceVersion(
            @Param("id") UUID id,
            @Param("fileId") UUID fileId,
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("versionNo") long versionNo,
            @Param("objectKey") String objectKey,
            @Param("sha256") String sha256);

    @Update("UPDATE files SET updated_at = #{updatedAt} WHERE id = #{id}")
    int updateFileTimestamp(@Param("id") UUID id, @Param("updatedAt") OffsetDateTime updatedAt);

    @Update("UPDATE files SET current_version_id = #{versionId} WHERE id = #{id}")
    int updateCurrentFileVersion(@Param("id") UUID id, @Param("versionId") UUID versionId);

    record OutboxState(
            int attempts,
            OffsetDateTime publishedAt,
            String lockedBy,
            OffsetDateTime nextAttemptAt,
            OffsetDateTime deadLetteredAt,
            String lastError) {}

    record MemoryState(String syncStatus, int syncAttempts, String lastError) {}

    record SandboxState(
            String status,
            String backendReleaseStatus,
            int backendReleaseAttempts,
            OffsetDateTime backendReleasedAt,
            String backendReleaseError) {}

    record TaskState(String status, String outputJson) {}

    record RunState(
            String status,
            UUID sessionId,
            Long consumedTokens,
            Long consumedCostMicros,
            Integer consumedModelCalls,
            String failureCode) {}

    record AgentRunState(String status, Integer depth, UUID parentAgentRunId) {}
}
