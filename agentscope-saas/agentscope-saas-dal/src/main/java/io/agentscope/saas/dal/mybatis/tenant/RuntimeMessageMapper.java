/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.dal.mybatis.tenant;

import io.agentscope.saas.domain.memory.RuntimeMessageRepository.Hit;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository.Identity;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository.Message;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository.Scope;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface RuntimeMessageMapper {
    String OWNER = "org_id = #{scope.orgId} AND user_id = #{scope.userId}";
    String STREAM_SCOPE =
            OWNER
                    + " AND agent_id = #{scope.agentId} AND session_id = #{scope.sessionId} AND"
                    + " agent_label = #{scope.agentLabel} AND session_key = #{scope.sessionKey}";
    String STREAM_COLUMNS =
            "SELECT id, org_id, user_id, agent_id, session_id, agent_label, session_key, last_seq,"
                    + " last_message_id FROM runtime_message_streams";

    @Select(
            "SELECT id FROM chat_sessions WHERE id = #{scope.sessionId} AND agent_id ="
                    + " #{scope.agentId} AND "
                    + OWNER
                    + " FOR UPDATE")
    List<UUID> lockSession(@Param("scope") Scope scope);

    @Insert(
            """
            INSERT INTO runtime_message_streams(id, org_id, user_id, agent_id, session_id, agent_label, session_key)
            VALUES(#{id}, #{scope.orgId}, #{scope.userId}, #{scope.agentId}, #{scope.sessionId}, #{scope.agentLabel}, #{scope.sessionKey})
            ON CONFLICT DO NOTHING
            """)
    int ensureStream(@Param("id") UUID id, @Param("scope") Scope scope);

    @Select(STREAM_COLUMNS + " WHERE " + STREAM_SCOPE + " FOR UPDATE")
    RuntimeStreamData lockStream(@Param("scope") Scope scope);

    @Select(STREAM_COLUMNS + " WHERE " + STREAM_SCOPE)
    List<RuntimeStreamData> findExactStream(@Param("scope") Scope scope);

    @Select(
            "SELECT seq, message_id, content_hash FROM runtime_messages WHERE stream_id ="
                    + " #{streamId} AND "
                    + OWNER
                    + " AND message_id = #{messageId}")
    List<Identity> findIdentity(
            @Param("streamId") UUID streamId,
            @Param("scope") Scope scope,
            @Param("messageId") String messageId);

    @Select(
            """
            <script>SELECT seq, message_id, content_hash FROM runtime_messages
             WHERE stream_id = #{streamId} AND org_id = #{scope.orgId} AND user_id = #{scope.userId}
             AND message_id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Identity> findIdentities(
            @Param("streamId") UUID streamId,
            @Param("scope") Scope scope,
            @Param("ids") List<String> ids);

    @Select(
            """
            <script>SELECT stream_id, seq, message_id, parent_message_id, source_run_id,
                source_agent_run_id, role, NULL AS payload_json, content_hash, content_bytes, created_at, body_id
              FROM runtime_messages WHERE stream_id = #{streamId}
               AND org_id = #{scope.orgId} AND user_id = #{scope.userId}
               AND message_id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Message> findMessageMetadata(
            @Param("streamId") UUID streamId,
            @Param("scope") Scope scope,
            @Param("ids") List<String> ids);

    @Select(
            """
            SELECT m.stream_id, m.seq, m.message_id, m.parent_message_id, m.source_run_id,
                   m.source_agent_run_id, m.role, m.payload_json, m.content_hash, m.content_bytes, m.created_at, m.body_id
              FROM runtime_messages m JOIN runtime_message_streams s ON s.id = m.stream_id
             WHERE s.org_id = #{scope.orgId} AND s.user_id = #{scope.userId}
               AND s.agent_id = #{scope.agentId} AND s.session_id = #{scope.sessionId}
               AND m.org_id = #{scope.orgId} AND m.user_id = #{scope.userId}
               AND m.message_id = #{messageId} AND m.content_hash = #{hash}
             ORDER BY m.created_at, m.seq LIMIT 1
            """)
    List<Message> findOwnedSource(
            @Param("scope") Scope scope,
            @Param("messageId") String messageId,
            @Param("hash") String hash);

    @Insert(
            """
            INSERT INTO runtime_messages(stream_id, org_id, user_id, seq, message_id, parent_message_id,
                source_run_id, source_agent_run_id, role, payload_json, content_hash, content_bytes, created_at, body_id)
            VALUES(#{message.streamId}, #{scope.orgId}, #{scope.userId}, #{message.seq}, #{message.messageId},
                #{message.parentMessageId}, #{message.sourceRunId}, #{message.sourceAgentRunId}, #{message.role},
                #{message.payloadJson}, #{message.contentHash}, #{message.contentBytes}, #{message.createdAt}, #{message.bodyId})
            """)
    int insert(@Param("scope") Scope scope, @Param("message") Message message);

    @Update(
            "UPDATE runtime_message_streams SET last_seq = #{seq}, last_message_id = #{messageId},"
                    + " updated_at = CURRENT_TIMESTAMP WHERE id = #{streamId} AND "
                    + STREAM_SCOPE)
    int advance(
            @Param("scope") Scope scope,
            @Param("streamId") UUID streamId,
            @Param("seq") long seq,
            @Param("messageId") String messageId);

    @Select(
            STREAM_COLUMNS
                    + " WHERE org_id = #{orgId} AND user_id = #{userId} AND agent_id = #{agentId}"
                    + " AND agent_label = #{agentLabel} AND session_key = #{sessionKey}")
    List<RuntimeStreamData> findStream(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("agentId") UUID agentId,
            @Param("agentLabel") String agentLabel,
            @Param("sessionKey") String sessionKey);

    @Select(
            """
            <script>
            WITH candidates AS (
                SELECT seq, content_bytes FROM runtime_messages
                 WHERE stream_id = #{streamId} AND org_id = #{scope.orgId} AND user_id = #{scope.userId}
                 <if test="after != null">AND seq &gt; #{after}</if>
                 <if test="before != null">AND seq &lt; #{before}</if>
                 ORDER BY seq <choose><when test="after != null">ASC</when><otherwise>DESC</otherwise></choose>
                 LIMIT #{limit}
            ), sizes AS (
                SELECT seq, SUM(content_bytes) OVER (ORDER BY seq
                <choose><when test="after != null">ASC</when><otherwise>DESC</otherwise></choose>) AS running_bytes
                FROM candidates
            )
            SELECT m.stream_id, m.seq, m.message_id, m.parent_message_id, m.source_run_id,
                   m.source_agent_run_id, m.role, m.payload_json, m.content_hash, m.content_bytes, m.created_at, m.body_id
              FROM runtime_messages m JOIN sizes s ON s.seq = m.seq
             WHERE m.stream_id = #{streamId} AND m.org_id = #{scope.orgId} AND m.user_id = #{scope.userId}
               AND s.running_bytes &lt;= #{maxBytes}
             ORDER BY m.seq ASC
            </script>
            """)
    List<Message> window(
            @Param("scope") Scope scope,
            @Param("streamId") UUID streamId,
            @Param("after") Long after,
            @Param("before") Long before,
            @Param("limit") int limit,
            @Param("maxBytes") long maxBytes);

    @Select(
            """
            <script>
            SELECT seq FROM runtime_messages WHERE stream_id = #{streamId}
             AND org_id = #{scope.orgId} AND user_id = #{scope.userId}
             <if test="after != null">AND seq &gt; #{after}</if>
             <if test="before != null">AND seq &lt; #{before}</if>
             LIMIT 1
            </script>
            """)
    List<Long> hasBeyond(
            @Param("scope") Scope scope,
            @Param("streamId") UUID streamId,
            @Param("after") Long after,
            @Param("before") Long before);

    @Select(
            """
            <script>
            SELECT s.agent_label, s.session_key, m.seq, m.role, LEFT(m.payload_json, 2048) AS preview
              FROM runtime_messages m JOIN runtime_message_streams s ON s.id = m.stream_id
             WHERE s.org_id = #{orgId} AND s.user_id = #{userId} AND s.agent_id = #{agentId}
               AND m.org_id = #{orgId} AND m.user_id = #{userId}
               <if test="agentLabel != null">AND s.agent_label = #{agentLabel}</if>
               AND LOWER(m.payload_json) LIKE #{pattern} ESCAPE '!'
             ORDER BY m.created_at DESC, m.stream_id, m.seq DESC LIMIT #{limit}
            </script>
            """)
    List<Hit> search(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("agentId") UUID agentId,
            @Param("agentLabel") String agentLabel,
            @Param("pattern") String pattern,
            @Param("limit") int limit);

    @Select(
            """
            <script>
            """
                    + STREAM_COLUMNS
                    + """
                     WHERE org_id = #{orgId} AND user_id = #{userId} AND agent_id = #{agentId}
                     <if test="agentLabel != null">AND agent_label = #{agentLabel}</if>
                     ORDER BY updated_at DESC, id LIMIT #{limit}
                    </script>
                    """)
    List<RuntimeStreamData> list(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("agentId") UUID agentId,
            @Param("agentLabel") String agentLabel,
            @Param("limit") int limit);

    @Delete(
            "DELETE FROM runtime_message_streams WHERE org_id = #{orgId} AND user_id = #{userId}"
                    + " AND session_id = #{sessionId}")
    int deleteSession(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("sessionId") UUID sessionId);
}
