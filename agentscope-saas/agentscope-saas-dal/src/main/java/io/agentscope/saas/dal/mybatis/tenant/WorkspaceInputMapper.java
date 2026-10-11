/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.mybatis.tenant;

import io.agentscope.saas.domain.workspace.WorkspaceInputRepository.Input;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Scope;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface WorkspaceInputMapper {
    @Select(
            """
              SELECT a.id AS attachment_id, a.file_id, a.file_version_id AS version_id, a.logical_path,
                     v.storage_backend AS backend, v.object_key, v.size_bytes, v.sha256
                FROM assistant_runs r JOIN file_attachments a
                  ON a.org_id = r.org_id AND a.user_id = r.user_id AND a.agent_id = r.agent_id AND a.session_id = r.session_id
            LEFT JOIN files f ON f.id = a.file_id AND f.org_id = a.org_id AND f.user_id = a.user_id
            LEFT JOIN file_versions v ON v.id = a.file_version_id AND v.file_id = a.file_id AND v.org_id = a.org_id AND v.user_id = a.user_id AND f.id IS NOT NULL
                WHERE r.id = #{s.runId} AND r.org_id = #{s.orgId} AND r.user_id = #{s.userId} AND r.agent_id = #{s.agentId}
                  AND r.session_id = #{s.sessionId} AND r.session_generation = #{s.sessionGeneration}
                  AND ((r.trigger_message_id IS NOT NULL AND a.message_id = r.trigger_message_id) OR a.task_id = #{s.execution.taskId})
                ORDER BY a.id LIMIT #{limit}
            """)
    List<Input> bindings(@Param("s") Scope scope, @Param("limit") int limit);
}
