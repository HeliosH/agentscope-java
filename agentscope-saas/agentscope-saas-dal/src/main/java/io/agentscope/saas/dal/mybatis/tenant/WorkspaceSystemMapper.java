/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.mybatis.tenant;

import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Scope;
import io.agentscope.saas.domain.workspace.WorkspaceSystemRepository.Entry;
import io.agentscope.saas.domain.workspace.WorkspaceSystemRepository.Manifest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface WorkspaceSystemMapper {
    String OWNED =
            "run_id = #{s.runId} AND org_id = #{s.orgId} AND user_id = #{s.userId} AND agent_id ="
                    + " #{s.agentId} AND session_id = #{s.sessionId} AND session_generation ="
                    + " #{s.sessionGeneration}";

    @Select("SELECT version,file_count,total_bytes FROM run_system_manifests WHERE " + OWNED)
    Manifest find(@Param("s") Scope scope);

    @Select(
            "SELECT e.logical_path AS"
                + " path,e.publication_id,e.backend,e.object_key,e.size_bytes,e.sha256 FROM"
                + " run_system_entries e WHERE e.run_id IN (SELECT run_id FROM run_system_manifests"
                + " WHERE "
                    + OWNED
                    + ") ORDER BY e.logical_path LIMIT #{limit}")
    List<Entry> entries(@Param("s") Scope scope, @Param("limit") int limit);

    @Select(
            """
            SELECT COALESCE((SELECT SUM(total_bytes) FROM run_system_manifests WHERE org_id = #{org}),0)
                 + COALESCE((SELECT SUM(size_bytes) FROM file_publications p WHERE org_id = #{org}
                   AND logical_path LIKE '/.agentscope/system-snapshots/%' AND status IN ('STAGED','STORED')
                   AND lease_until > #{now} AND NOT EXISTS (SELECT 1 FROM run_system_entries e WHERE e.publication_id = p.id)),0)
            """)
    long occupied(@Param("org") UUID org, @Param("now") OffsetDateTime now);

    @Insert(
            """
            INSERT INTO run_system_manifests(run_id,org_id,user_id,agent_id,session_id,session_generation,version,file_count,total_bytes)
            VALUES(#{s.runId},#{s.orgId},#{s.userId},#{s.agentId},#{s.sessionId},#{s.sessionGeneration},#{m.version},#{m.fileCount},#{m.totalBytes})
            """)
    int insert(@Param("s") Scope scope, @Param("m") Manifest manifest);

    @Insert(
            """
            INSERT INTO run_system_entries(run_id,org_id,logical_path,publication_id,backend,object_key,size_bytes,sha256)
            VALUES(#{s.runId},#{s.orgId},#{e.path},#{e.publicationId},#{e.backend},#{e.objectKey},#{e.sizeBytes},#{e.sha256})
            """)
    int insertEntry(@Param("s") Scope scope, @Param("e") Entry entry);
}
