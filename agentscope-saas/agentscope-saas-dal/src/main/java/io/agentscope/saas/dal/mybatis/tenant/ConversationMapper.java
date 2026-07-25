/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.mybatis.tenant;

import io.agentscope.saas.domain.model.AgentEntity;
import io.agentscope.saas.domain.model.ChatMessageEntity;
import io.agentscope.saas.domain.model.ChatSessionEntity;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Tenant mapper for the Agent and Conversation aggregates. */
public interface ConversationMapper {

    String AGENT_COLUMNS =
            """
            SELECT id, org_id, user_id, name, visibility, status, description, sys_prompt,
                   max_iters, tools, workspace_path, builtin, created_at, updated_at
              FROM agents
            """;

    @Select(AGENT_COLUMNS + " WHERE org_id = #{orgId} ORDER BY id")
    List<AgentEntity> findAgentsByOrg(@Param("orgId") UUID orgId);

    @Select(AGENT_COLUMNS + " WHERE id = #{id} AND org_id = #{orgId}")
    List<AgentEntity> findAgent(@Param("id") UUID id, @Param("orgId") UUID orgId);

    @Select(
            AGENT_COLUMNS
                    + """
                     WHERE id = #{id} AND org_id = #{orgId} AND user_id = #{userId}
                     FOR UPDATE
                    """)
    List<AgentEntity> lockOwnedAgent(
            @Param("id") UUID id, @Param("orgId") UUID orgId, @Param("userId") UUID userId);

    @Select(AGENT_COLUMNS + " WHERE org_id = #{orgId} AND user_id = #{userId} ORDER BY id ASC")
    List<AgentEntity> findOwnedAgentsById(@Param("orgId") UUID orgId, @Param("userId") UUID userId);

    @Select(
            AGENT_COLUMNS
                    + """
                     WHERE org_id = #{orgId} AND user_id = #{userId}
                     ORDER BY updated_at DESC, id ASC
                    """)
    List<AgentEntity> findOwnedAgentsByUpdatedAt(
            @Param("orgId") UUID orgId, @Param("userId") UUID userId);

    @Select(
            AGENT_COLUMNS
                    + """
                     WHERE org_id = #{orgId} AND user_id = #{userId} AND name = #{name}
                     LIMIT 1
                    """)
    List<AgentEntity> findOwnedAgentByName(
            @Param("orgId") UUID orgId, @Param("userId") UUID userId, @Param("name") String name);

    @Insert(
            """
            INSERT INTO agents
                (id, org_id, user_id, name, visibility, status, description, sys_prompt,
                 max_iters, tools, workspace_path, builtin, updated_at)
            VALUES
                (#{id}, #{orgId}, #{userId}, #{name}, #{visibility}, #{status}, #{description},
                 #{sysPrompt}, #{maxIters},
                 #{tools,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler},
                 #{workspacePath}, #{builtin}, #{updatedAt})
            """)
    int insertAgent(AgentEntity agent);

    @Update(
            """
            UPDATE agents
               SET name = #{name}, visibility = #{visibility}, status = #{status},
                   description = #{description}, sys_prompt = #{sysPrompt},
                   max_iters = #{maxIters},
                   tools = #{tools,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler},
                   workspace_path = #{workspacePath}, builtin = #{builtin},
                   updated_at = #{updatedAt}
             WHERE id = #{id} AND org_id = #{orgId}
            """)
    int updateAgent(AgentEntity agent);

    @Delete("DELETE FROM agents WHERE id = #{id} AND org_id = #{orgId}")
    int deleteAgent(@Param("id") UUID id, @Param("orgId") UUID orgId);

    String SESSION_COLUMNS =
            """
            SELECT id, org_id, user_id, agent_id, title, message_count, source, label,
                   unread, last_message, created_at, updated_at
              FROM chat_sessions
            """;

    @Select(
            SESSION_COLUMNS
                    + """
                     WHERE org_id = #{orgId} AND user_id = #{userId} AND agent_id = #{agentId}
                     ORDER BY updated_at DESC, id ASC
                    """)
    List<ChatSessionEntity> findSessions(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("agentId") UUID agentId);

    @Select(SESSION_COLUMNS + " WHERE id = #{id} AND org_id = #{orgId} AND user_id = #{userId}")
    List<ChatSessionEntity> findOwnedSession(
            @Param("id") UUID id, @Param("orgId") UUID orgId, @Param("userId") UUID userId);

    @Select(
            SESSION_COLUMNS
                    + """
                     WHERE id = #{id} AND org_id = #{orgId} AND user_id = #{userId}
                       AND agent_id = #{agentId}
                    """)
    List<ChatSessionEntity> findOwnedAgentSession(
            @Param("id") UUID id,
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("agentId") UUID agentId);

    @Select(
            SESSION_COLUMNS
                    + """
                     WHERE org_id = #{orgId} AND user_id = #{userId} AND agent_id = #{agentId}
                     ORDER BY updated_at DESC, id ASC LIMIT 1
                    """)
    List<ChatSessionEntity> findLatestSession(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("agentId") UUID agentId);

    @Select(SESSION_COLUMNS + " WHERE id = #{id}")
    List<ChatSessionEntity> findSession(@Param("id") UUID id);

    @Select(SESSION_COLUMNS + " WHERE id = #{id} FOR UPDATE")
    List<ChatSessionEntity> lockSession(@Param("id") UUID id);

    @Insert(
            """
            INSERT INTO chat_sessions
                (id, org_id, user_id, agent_id, title, message_count, source, label,
                 unread, last_message, updated_at)
            VALUES
                (#{id}, #{orgId}, #{userId}, #{agentId}, #{title}, #{messageCount}, #{source},
                 #{label}, #{unread}, #{lastMessage}, #{updatedAt})
            """)
    int insertSession(ChatSessionEntity session);

    @Update(
            """
            UPDATE chat_sessions
               SET title = #{title}, message_count = #{messageCount}, source = #{source},
                   label = #{label}, unread = #{unread}, last_message = #{lastMessage},
                   updated_at = #{updatedAt}
             WHERE id = #{id} AND org_id = #{orgId}
            """)
    int updateSession(ChatSessionEntity session);

    @Delete("DELETE FROM chat_sessions WHERE id = #{id}")
    int deleteSession(@Param("id") UUID id);

    String MESSAGE_COLUMNS =
            """
            SELECT id, org_id, user_id, session_id, agent_id, seq, role, content_json,
                   parent_id, tool_name, source_run_id, tool_input, tool_result, created_at
              FROM chat_messages
            """;

    @Select("SELECT COUNT(*) FROM chat_messages WHERE session_id = #{sessionId}")
    long countMessages(@Param("sessionId") UUID sessionId);

    @Select(MESSAGE_COLUMNS + " WHERE source_run_id = #{sourceRunId} LIMIT 1")
    List<ChatMessageEntity> findMessageBySourceRun(@Param("sourceRunId") UUID sourceRunId);

    @Select(MESSAGE_COLUMNS + " WHERE session_id = #{sessionId} ORDER BY created_at ASC, id ASC")
    List<ChatMessageEntity> findMessagesByCreatedAt(@Param("sessionId") UUID sessionId);

    @Select(MESSAGE_COLUMNS + " WHERE session_id = #{sessionId} ORDER BY seq ASC")
    List<ChatMessageEntity> findMessagesBySeq(@Param("sessionId") UUID sessionId);

    @Select(
            """
            <script>
            """
                    + MESSAGE_COLUMNS
                    + """
                     WHERE session_id = #{sessionId}
                     <if test="afterSeq != null">AND seq &gt; #{afterSeq}</if>
                     ORDER BY seq ASC LIMIT #{limit}
                    </script>
                    """)
    List<ChatMessageEntity> pageMessagesAfter(
            @Param("sessionId") UUID sessionId,
            @Param("afterSeq") Long afterSeq,
            @Param("limit") int limit);

    @Select(
            """
            <script>
            """
                    + MESSAGE_COLUMNS
                    + """
                     WHERE session_id = #{sessionId}
                     <if test="beforeSeq != null">AND seq &lt; #{beforeSeq}</if>
                     ORDER BY seq DESC LIMIT #{limit}
                    </script>
                    """)
    List<ChatMessageEntity> pageMessagesBefore(
            @Param("sessionId") UUID sessionId,
            @Param("beforeSeq") Long beforeSeq,
            @Param("limit") int limit);

    @Select("SELECT COALESCE(MAX(seq), 0) FROM chat_messages WHERE session_id = #{sessionId}")
    long maxMessageSeq(@Param("sessionId") UUID sessionId);

    @Insert(
            """
            INSERT INTO chat_messages
                (id, org_id, user_id, session_id, agent_id, seq, role, content_json, parent_id,
                 tool_name, source_run_id, tool_input, tool_result)
            VALUES
                (#{id}, #{orgId}, #{userId}, #{sessionId}, #{agentId}, #{seq}, #{role},
                 #{contentJson,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler},
                 #{parentId}, #{toolName}, #{sourceRunId},
                 #{toolInput,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler},
                 #{toolResult,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler})
            """)
    int insertMessage(ChatMessageEntity message);

    @Delete("DELETE FROM chat_messages WHERE session_id = #{sessionId}")
    int deleteMessages(@Param("sessionId") UUID sessionId);
}
