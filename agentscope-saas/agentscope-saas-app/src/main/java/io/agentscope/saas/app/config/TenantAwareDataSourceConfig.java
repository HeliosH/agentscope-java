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
package io.agentscope.saas.app.config;

import io.agentscope.saas.core.tenant.TenantContextHolder;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

/**
 * Configures the primary {@link DataSource} so every connection checkout sets the PostgreSQL GUC
 * {@code app.current_org} from {@link TenantContextHolder}, driving Row-Level Security (Flyway V6).
 *
 * <p>HikariCP 5.x removed {@code IConnectionCustomizer}, so we wrap the pool in a {@link
 * TransactionAwareDataSourceProxy} subclass that issues {@code SET app.current_org} on each {@code
 * getConnection()}. The GUC is session-scoped and overwritten on every checkout, so pooled
 * connections never inherit a prior tenant. When the holder is empty (Flyway migrations, system
 * calls), the GUC is reset to empty → {@code current_setting('app.current_org', true)} returns NULL
 * → RLS denies all tenant rows (safe default). The app DB role must NOT be a superuser/BYPASSRLS.
 */
@Configuration
public class TenantAwareDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareDataSourceConfig.class);

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource(DataSourceProperties properties) {
        DataSource pool =
                properties
                        .initializeDataSourceBuilder()
                        .type(com.zaxxer.hikari.HikariDataSource.class)
                        .build();
        log.info("Tenant-aware DataSource configured (RLS app.current_org set on each checkout)");
        return new TenantRlsDataSourceProxy(pool);
    }

    /**
     * Wraps a pool and sets {@code app.current_org} on every {@link #getConnection()} from the
     * current {@link TenantContextHolder}. Extends {@link TransactionAwareDataSourceProxy} so JPA
     * transaction synchronization still works.
     */
    static final class TenantRlsDataSourceProxy extends TransactionAwareDataSourceProxy {

        TenantRlsDataSourceProxy(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection c = super.getConnection();
            applyTenantGuc(c);
            return c;
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            Connection c = super.getConnection(username, password);
            applyTenantGuc(c);
            return c;
        }

        private static void applyTenantGuc(Connection conn) {
            String orgId = TenantContextHolder.getOrgId();
            String value = sanitize(orgId);
            try (Statement st = conn.createStatement()) {
                // Empty → current_setting(..., true) returns NULL → RLS denies all tenant rows.
                st.execute("SET app.current_org = '" + value + "'");
            } catch (SQLException e) {
                log.warn("Failed to SET app.current_org on connection: {}", e.getMessage());
            }
        }

        private static String sanitize(String orgId) {
            if (orgId == null || orgId.isBlank()) return "";
            try {
                // Validate UUID format; if it parses, it is injection-safe.
                return UUID.fromString(orgId).toString();
            } catch (IllegalArgumentException e) {
                return "";
            }
        }
    }
}
