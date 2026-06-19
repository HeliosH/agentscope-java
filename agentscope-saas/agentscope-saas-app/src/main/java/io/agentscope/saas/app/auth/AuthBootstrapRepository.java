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
package io.agentscope.saas.app.auth;

import io.agentscope.saas.core.persistence.entity.OrgEntity;
import io.agentscope.saas.core.persistence.entity.UserEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Bootstrap queries for {@link AuthController} login/register, executed against the admin (RLS-bypass)
 * {@link DataSource} (see {@link io.agentscope.saas.app.config.AdminDataSourceConfig}). These run
 * before a tenant context exists, so they cannot go through the RLS-wrapped primary DataSource —
 * with no {@code app.current_org} set, Row-Level Security would deny all rows and login would always
 * fail. The bypass is intentionally confined to this class: only user lookup by email, org lookup by
 * slug, and user creation on register.
 */
@Repository
public class AuthBootstrapRepository {

    private final JdbcTemplate jdbc;

    public AuthBootstrapRepository(@Qualifier("adminDataSource") DataSource adminDataSource) {
        this.jdbc = new JdbcTemplate(adminDataSource);
    }

    private static final RowMapper<UserEntity> USER_MAPPER =
            (rs, n) -> {
                UserEntity u = new UserEntity();
                u.setId(rs.getObject("id", UUID.class));
                u.setOrgId(rs.getObject("org_id", UUID.class));
                u.setEmail(rs.getString("email"));
                u.setDisplayName(rs.getString("display_name"));
                u.setPasswordHash(rs.getString("password_hash"));
                u.setRole(rs.getString("role"));
                u.setTier(rs.getString("tier"));
                return u;
            };

    private static final RowMapper<OrgEntity> ORG_MAPPER =
            (rs, n) -> {
                OrgEntity o = new OrgEntity();
                o.setId(rs.getObject("id", UUID.class));
                o.setName(rs.getString("name"));
                o.setSlug(rs.getString("slug"));
                o.setStatus(rs.getString("status"));
                return o;
            };

    /** Find a user by email, bypassing RLS (login credential lookup). */
    public Optional<UserEntity> findUserByEmail(String email) {
        List<UserEntity> rows =
                jdbc.query(
                        "SELECT id, org_id, email, display_name, password_hash, role, tier"
                                + " FROM users WHERE email = ?",
                        USER_MAPPER,
                        email);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Find an org by slug, bypassing RLS (resolves the default org on register). */
    public Optional<OrgEntity> findOrgBySlug(String slug) {
        List<OrgEntity> rows =
                jdbc.query(
                        "SELECT id, name, slug, status FROM orgs WHERE slug = ?", ORG_MAPPER, slug);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Insert a new user, bypassing RLS (register). The id is app-generated; created_at is DB-defaulted. */
    public UserEntity saveUser(UserEntity user) {
        jdbc.update(
                "INSERT INTO users (id, org_id, email, display_name, password_hash, role, tier)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                user.getId(),
                user.getOrgId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getPasswordHash(),
                user.getRole(),
                user.getTier());
        return user;
    }
}
