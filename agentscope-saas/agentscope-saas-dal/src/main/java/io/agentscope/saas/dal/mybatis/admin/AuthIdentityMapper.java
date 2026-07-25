/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.dal.mybatis.admin;

import java.util.List;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** SQL mapper for the deliberately narrow pre-tenant identity bootstrap path. */
public interface AuthIdentityMapper {

    @Select(
            """
            SELECT id, org_id, email, display_name, password_hash, role, tier
              FROM users
             WHERE email = #{email}
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = java.util.UUID.class),
        @Arg(column = "org_id", javaType = java.util.UUID.class),
        @Arg(column = "email", javaType = String.class),
        @Arg(column = "display_name", javaType = String.class),
        @Arg(column = "password_hash", javaType = String.class),
        @Arg(column = "role", javaType = String.class),
        @Arg(column = "tier", javaType = String.class)
    })
    List<AuthIdentityData.User> findUserByEmail(@Param("email") String email);

    @Select(
            """
            SELECT id, name, slug, status
              FROM orgs
             WHERE slug = #{slug}
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = java.util.UUID.class),
        @Arg(column = "name", javaType = String.class),
        @Arg(column = "slug", javaType = String.class),
        @Arg(column = "status", javaType = String.class)
    })
    List<AuthIdentityData.Org> findOrganizationBySlug(@Param("slug") String slug);

    @Insert(
            """
            INSERT INTO users (id, org_id, email, display_name, password_hash, role, tier)
            VALUES (#{id}, #{orgId}, #{email}, #{displayName}, #{passwordHash}, #{role}, #{tier})
            """)
    int insertUser(
            @Param("id") java.util.UUID id,
            @Param("orgId") java.util.UUID orgId,
            @Param("email") String email,
            @Param("displayName") String displayName,
            @Param("passwordHash") String passwordHash,
            @Param("role") String role,
            @Param("tier") String tier);
}
