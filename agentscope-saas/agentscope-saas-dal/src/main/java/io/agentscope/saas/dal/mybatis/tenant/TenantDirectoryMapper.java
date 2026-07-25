/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.mybatis.tenant;

import io.agentscope.saas.domain.model.OrgEntity;
import io.agentscope.saas.domain.model.TierPolicyEntity;
import io.agentscope.saas.domain.model.UserEntity;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Tenant mapper for organization, user, and quota policy domain data. */
public interface TenantDirectoryMapper {

    String ORG_COLUMNS = "SELECT id, name, slug, status, settings, created_at FROM orgs";

    @Select(ORG_COLUMNS + " WHERE slug = #{slug}")
    List<OrgEntity> findOrgBySlug(@Param("slug") String slug);

    @Select(ORG_COLUMNS + " WHERE id = #{id}")
    List<OrgEntity> findOrg(@Param("id") UUID id);

    @Select(ORG_COLUMNS + " WHERE id = #{id} FOR UPDATE")
    List<OrgEntity> lockOrg(@Param("id") UUID id);

    @Insert(
            """
            INSERT INTO orgs (id, name, slug, status, settings)
            VALUES (#{id}, #{name}, #{slug}, #{status},
                    #{settings,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler})
            """)
    int insertOrg(OrgEntity org);

    @Update(
            """
            UPDATE orgs
               SET name = #{name}, slug = #{slug}, status = #{status},
                   settings = #{settings,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler}
             WHERE id = #{id}
            """)
    int updateOrg(OrgEntity org);

    String USER_COLUMNS =
            """
            SELECT id, org_id, email, idp_subject, display_name, password_hash, role, tier,
                   created_at
              FROM users
            """;

    @Select(USER_COLUMNS + " WHERE email = #{email} LIMIT 1")
    List<UserEntity> findUserByEmail(@Param("email") String email);

    @Select(USER_COLUMNS + " WHERE idp_subject = #{subject} LIMIT 1")
    List<UserEntity> findUserBySubject(@Param("subject") String subject);

    @Select(
            USER_COLUMNS
                    + """
                     WHERE org_id = #{orgId}
                     ORDER BY created_at DESC, id ASC LIMIT #{limit}
                    """)
    List<UserEntity> findUsers(@Param("orgId") UUID orgId, @Param("limit") int limit);

    @Select(USER_COLUMNS + " WHERE org_id = #{orgId} AND id = #{id}")
    List<UserEntity> findUser(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Select("SELECT COUNT(*) FROM users WHERE org_id = #{orgId} AND role = #{role}")
    long countUsersByRole(@Param("orgId") UUID orgId, @Param("role") String role);

    @Select(USER_COLUMNS + " WHERE org_id = #{orgId} AND id = #{id} FOR UPDATE")
    List<UserEntity> lockUser(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Insert(
            """
            INSERT INTO users
                (id, org_id, email, idp_subject, display_name, password_hash, role, tier)
            VALUES
                (#{id}, #{orgId}, #{email}, #{idpSubject}, #{displayName}, #{passwordHash},
                 #{role}, #{tier})
            """)
    int insertUser(UserEntity user);

    @Update(
            """
            UPDATE users
               SET email = #{email}, idp_subject = #{idpSubject}, display_name = #{displayName},
                   password_hash = #{passwordHash}, role = #{role}, tier = #{tier}
             WHERE id = #{id} AND org_id = #{orgId}
            """)
    int updateUser(UserEntity user);

    @Select(
            """
            SELECT tier, max_agents, max_sandboxes, monthly_token_quota, storage_gb,
                   idle_ttl_seconds
              FROM tier_policies
             WHERE tier = #{tier}
            """)
    List<TierPolicyEntity> findTier(@Param("tier") String tier);

    @Select(
            """
            SELECT tier, max_agents, max_sandboxes, monthly_token_quota, storage_gb,
                   idle_ttl_seconds
              FROM tier_policies
             ORDER BY tier ASC
            """)
    List<TierPolicyEntity> findTiers();

    @Insert(
            """
            INSERT INTO tier_policies
                (tier, max_agents, max_sandboxes, monthly_token_quota, storage_gb,
                 idle_ttl_seconds)
            VALUES
                (#{tier}, #{maxAgents}, #{maxSandboxes}, #{monthlyTokenQuota}, #{storageGb},
                 #{idleTtlSeconds})
            """)
    int insertTier(TierPolicyEntity policy);

    @Update(
            """
            UPDATE tier_policies
               SET max_agents = #{maxAgents}, max_sandboxes = #{maxSandboxes},
                   monthly_token_quota = #{monthlyTokenQuota}, storage_gb = #{storageGb},
                   idle_ttl_seconds = #{idleTtlSeconds}
             WHERE tier = #{tier}
            """)
    int updateTier(TierPolicyEntity policy);
}
