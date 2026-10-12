/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.support;

import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** SQL fixtures only; production writes use the Domain ports and DAL adapters. */
public interface SandboxUsageTestMapper {
    @Delete("DELETE FROM sandbox_runtime_resources WHERE org_id = #{org}")
    void deleteResources(@Param("org") UUID org);

    @Update(
            "UPDATE sandbox_usage_refs SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1' HOUR WHERE"
                    + " id = #{id}")
    void expire(@Param("id") UUID id);

    @Select("SELECT id FROM sandbox_runtime_resources WHERE id = #{id} FOR UPDATE")
    UUID lockResource(@Param("id") UUID id);

    @Update(
            "UPDATE sandbox_usage_refs SET expires_at = CURRENT_TIMESTAMP + INTERVAL '1' HOUR WHERE"
                    + " id = #{id}")
    void renew(@Param("id") UUID id);

    @Update("UPDATE sandbox_runtime_resources SET user_id = #{user} WHERE id = #{id}")
    void rewriteOwner(@Param("id") UUID id, @Param("user") UUID user);
}
