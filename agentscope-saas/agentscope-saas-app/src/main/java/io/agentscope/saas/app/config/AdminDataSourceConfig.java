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

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The admin/bypass {@link DataSource}: a plain Hikari pool that connects as the superuser
 * {@code agentscope} role and does <strong>not</strong> set the RLS GUC {@code app.current_org}, so
 * it bypasses Row-Level Security. Used ONLY for bootstrap queries that run before a tenant context
 * exists (login/register — see {@link
 * io.agentscope.saas.app.auth.AuthBootstrapRepository}). Every authenticated request continues to
 * use the {@code @Primary} RLS-wrapped {@link DataSource} from {@link TenantAwareDataSourceConfig}.
 *
 * <p>The bypass is explicit and narrow by design: there is no routing layer, so an empty tenant
 * holder on an authenticated path can never accidentally reach this DataSource — it stays on the
 * RLS-wrapped primary and is denied. On H2 profiles (no RLS) this bean resolves to the same in-memory
 * DB and the bypass is a harmless no-op.
 */
@Configuration
@EnableConfigurationProperties(AdminDataSourceConfig.AdminDataSourceProperties.class)
public class AdminDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(AdminDataSourceConfig.class);

    @Bean
    @FlywayDataSource
    @ConfigurationProperties("saas.datasource.admin.hikari")
    public DataSource adminDataSource(AdminDataSourceProperties properties) {
        DataSource pool =
                DataSourceBuilder.create()
                        .type(HikariDataSource.class)
                        .url(properties.url())
                        .username(properties.username())
                        .password(properties.password())
                        .driverClassName(properties.driverClassName())
                        .build();
        log.info("Admin (RLS-bypass) DataSource configured for bootstrap queries (login/register)");
        return pool;
    }

    /** Connection settings for the admin/bypass DataSource ({@code saas.datasource.admin.*}). */
    @ConfigurationProperties("saas.datasource.admin")
    public record AdminDataSourceProperties(
            String url, String username, String password, String driverClassName) {}
}
