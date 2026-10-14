/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.mybatis.tenant;

import io.agentscope.saas.domain.workspace.WorkspaceInputRepository.Frozen;
import io.agentscope.saas.domain.workspace.WorkspaceInputRepository.Input;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Scope;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface WorkspaceInputMapper {
    @Select(
            """
            SELECT i.attachment_id,i.file_id,i.version_id,i.logical_path,
                   CASE WHEN f.id IS NOT NULL AND v.id IS NOT NULL THEN i.backend ELSE NULL END AS backend,
                   i.object_key,i.size_bytes,i.sha256,parent.task_id AS source_task_id
              FROM agent_runs child JOIN agent_runs parent ON parent.id = child.parent_agent_run_id AND parent.org_id = child.org_id AND parent.run_id = child.run_id
              JOIN task_input_manifests m ON m.task_id = parent.task_id AND m.org_id = parent.org_id AND m.run_id = parent.run_id
              JOIN (SELECT task_id,org_id,attachment_id,file_id,version_id,logical_path,backend,object_key,size_bytes,sha256 FROM task_input_entries
                    UNION ALL SELECT task_id,org_id,NULL AS attachment_id,file_id,version_id,logical_path,backend,object_key,size_bytes,sha256 FROM task_input_additions) i ON i.task_id = m.task_id AND i.org_id = m.org_id
              LEFT JOIN files f ON f.id = i.file_id AND f.org_id = m.org_id AND f.user_id = m.user_id AND f.status = 'active'
              LEFT JOIN file_versions v ON v.id = i.version_id AND v.file_id = i.file_id AND v.org_id = m.org_id AND v.user_id = m.user_id
             WHERE child.id = #{s.execution.agentRunId} AND child.task_id = #{s.execution.taskId}
               AND child.org_id = #{s.orgId} AND child.run_id = #{s.runId} AND m.user_id = #{s.userId}
               AND m.agent_id = #{s.agentId} AND m.session_id = #{s.sessionId} AND m.session_generation = #{s.sessionGeneration}
             ORDER BY i.logical_path LIMIT #{limit}
            """)
    List<Input> parentBindings(@Param("s") Scope scope, @Param("limit") int limit);

    @Select(
            """
              SELECT a.id AS attachment_id, a.file_id, a.file_version_id AS version_id, a.logical_path,
                     v.storage_backend AS backend, v.object_key, v.size_bytes, v.sha256, NULL AS source_task_id
                FROM assistant_runs r JOIN file_attachments a
                  ON a.org_id = r.org_id AND a.user_id = r.user_id AND a.agent_id = r.agent_id AND a.session_id = r.session_id
            LEFT JOIN files f ON f.id = a.file_id AND f.org_id = a.org_id AND f.user_id = a.user_id AND f.status = 'active'
            LEFT JOIN file_versions v ON v.id = a.file_version_id AND v.file_id = a.file_id AND v.org_id = a.org_id AND v.user_id = a.user_id AND f.id IS NOT NULL
                WHERE r.id = #{s.runId} AND r.org_id = #{s.orgId} AND r.user_id = #{s.userId} AND r.agent_id = #{s.agentId}
                  AND r.session_id = #{s.sessionId} AND r.session_generation = #{s.sessionGeneration}
                  AND ((r.trigger_message_id IS NOT NULL AND a.message_id = r.trigger_message_id) OR a.task_id = #{s.execution.taskId})
                ORDER BY a.id LIMIT #{limit}
            """)
    List<Input> bindings(@Param("s") Scope scope, @Param("limit") int limit);

    @Select(
            """
               SELECT NULL AS attachment_id, a.file_id, a.file_version_id AS version_id,
                      a.logical_path, v.storage_backend AS backend, v.object_key, v.size_bytes,
                      v.sha256, d.id AS source_task_id
                 FROM task_edges e JOIN task_nodes d ON d.id = e.from_task_id
                 JOIN assistant_runs r ON r.id = e.run_id
                 JOIN run_artifacts a ON a.task_id = d.id AND a.run_id = r.id AND a.org_id = r.org_id
            LEFT JOIN files f ON f.id = a.file_id AND f.org_id = r.org_id AND f.user_id = r.user_id AND f.status = 'active'
            LEFT JOIN file_versions v ON v.id = a.file_version_id AND v.file_id = a.file_id
                      AND v.org_id = r.org_id AND v.user_id = r.user_id AND f.id IS NOT NULL
                WHERE e.to_task_id = #{s.execution.taskId} AND e.org_id = #{s.orgId}
                  AND d.org_id = #{s.orgId} AND d.run_id = r.id AND d.status = 'SUCCEEDED'
                  AND r.id = #{s.runId} AND r.org_id = #{s.orgId} AND r.user_id = #{s.userId}
                  AND r.agent_id = #{s.agentId} AND r.session_id = #{s.sessionId}
                  AND r.session_generation = #{s.sessionGeneration}
                  AND (a.logical_path IS NOT NULL OR a.file_id IS NOT NULL OR a.file_version_id IS NOT NULL)
                ORDER BY d.id, a.id LIMIT #{limit}
            """)
    List<Input> dependencies(@Param("s") Scope scope, @Param("limit") int limit);

    String OWNED =
            "task_id = #{s.execution.taskId} AND run_id = #{s.runId} AND org_id = #{s.orgId} AND"
                    + " user_id = #{s.userId} AND agent_id = #{s.agentId} AND session_id ="
                    + " #{s.sessionId} AND session_generation = #{s.sessionGeneration}";

    @Select("SELECT version, file_count, total_bytes FROM task_input_manifests WHERE " + OWNED)
    Frozen frozen(@Param("s") Scope scope);

    @Select(
            """
               SELECT i.attachment_id, i.file_id, i.version_id, i.logical_path,
                      CASE WHEN v.id IS NOT NULL AND f.id IS NOT NULL THEN i.backend ELSE NULL END AS backend,
                      i.object_key, i.size_bytes, i.sha256, i.source_task_id
                 FROM task_input_entries i
            LEFT JOIN files f ON f.id = i.file_id AND f.org_id = i.org_id AND f.user_id = #{s.userId} AND f.status = 'active'
            LEFT JOIN file_versions v ON v.id = i.version_id AND v.file_id = i.file_id
                      AND v.org_id = i.org_id AND v.user_id = #{s.userId}
                WHERE i.task_id IN (SELECT task_id FROM task_input_manifests WHERE
            """
                    + OWNED
                    + ") ORDER BY i.logical_path LIMIT #{limit}")
    List<Input> frozenBindings(@Param("s") Scope scope, @Param("limit") int limit);

    @Insert(
            """
            INSERT INTO task_input_manifests(task_id, org_id, user_id, agent_id, run_id, session_id,
                session_generation, version, file_count, total_bytes)
            VALUES(#{s.execution.taskId}, #{s.orgId}, #{s.userId}, #{s.agentId}, #{s.runId},
                #{s.sessionId}, #{s.sessionGeneration}, #{m.version}, #{m.fileCount}, #{m.totalBytes})
            """)
    int insertManifest(@Param("s") Scope scope, @Param("m") Frozen manifest);

    @Insert(
            """
            INSERT INTO task_input_entries(task_id, org_id, logical_path, file_id, version_id,
                attachment_id, source_task_id, backend, object_key, size_bytes, sha256)
            VALUES(#{s.execution.taskId}, #{s.orgId}, #{f.logicalPath}, #{f.fileId}, #{f.versionId},
                #{f.attachmentId}, #{f.sourceTaskId}, #{f.backend}, #{f.objectKey}, #{f.sizeBytes}, #{f.sha256})
            """)
    int insertEntry(@Param("s") Scope scope, @Param("f") Input file);

    @Select(
            """
            SELECT NULL AS attachment_id,f.id AS file_id,v.id AS version_id,f.logical_path,
                   v.storage_backend AS backend,v.object_key,v.size_bytes,v.sha256,NULL AS source_task_id
              FROM files f JOIN file_versions v ON v.file_id = f.id AND v.org_id = f.org_id AND v.user_id = f.user_id
             WHERE f.id = #{file} AND v.id = #{version} AND f.org_id = #{s.orgId} AND f.user_id = #{s.userId} AND f.status = 'active'
            """)
    Input ownedVersion(
            @Param("s") Scope scope,
            @Param("file") java.util.UUID file,
            @Param("version") java.util.UUID version);

    @Select(
            """
            SELECT NULL AS attachment_id,i.file_id,i.version_id,i.logical_path,
                   CASE WHEN v.id IS NOT NULL AND f.id IS NOT NULL THEN i.backend ELSE NULL END AS backend,
                   i.object_key,i.size_bytes,i.sha256,NULL AS source_task_id
              FROM task_input_additions i
              LEFT JOIN files f ON f.id = i.file_id AND f.org_id = i.org_id AND f.user_id = #{s.userId} AND f.status = 'active'
              LEFT JOIN file_versions v ON v.id = i.version_id AND v.file_id = i.file_id AND v.org_id = i.org_id AND v.user_id = #{s.userId}
             WHERE i.task_id IN (SELECT task_id FROM task_input_manifests WHERE
            """
                    + OWNED
                    + ") ORDER BY i.sequence_no LIMIT #{limit}")
    List<Input> additions(@Param("s") Scope scope, @Param("limit") int limit);

    @Select(
            "SELECT COALESCE(MAX(sequence_no),0)+1 FROM task_input_additions WHERE task_id ="
                    + " #{s.execution.taskId} AND org_id = #{s.orgId}")
    long nextAddition(@Param("s") Scope scope);

    @Insert(
            """
            INSERT INTO task_input_additions(task_id,org_id,sequence_no,logical_path,file_id,version_id,backend,object_key,size_bytes,sha256)
            VALUES(#{s.execution.taskId},#{s.orgId},#{seq},#{f.logicalPath},#{f.fileId},#{f.versionId},#{f.backend},#{f.objectKey},#{f.sizeBytes},#{f.sha256})
            """)
    int append(@Param("s") Scope scope, @Param("f") Input file, @Param("seq") long sequence);

    @Select(
            """
            SELECT NULL AS attachment_id,f.id AS file_id,v.id AS version_id,f.logical_path,
                   v.storage_backend AS backend,v.object_key,v.size_bytes,v.sha256,NULL AS source_task_id
              FROM files f JOIN file_versions v ON v.id = f.current_version_id AND v.file_id = f.id AND v.org_id = f.org_id AND v.user_id = f.user_id
             WHERE f.org_id = #{s.orgId} AND f.user_id = #{s.userId} AND f.status = 'active'
               AND (f.logical_path LIKE 'inputs/%' OR f.logical_path LIKE 'outputs/%')
               AND f.logical_path LIKE #{pattern} ESCAPE '!' AND f.logical_path > #{after}
             ORDER BY f.logical_path LIMIT #{limit}
            """)
    List<Input> search(
            @Param("s") Scope scope,
            @Param("pattern") String pattern,
            @Param("after") String afterPath,
            @Param("limit") int limit);
}
