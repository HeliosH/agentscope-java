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

import io.agentscope.saas.app.auth.DevSecurityProperties;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantResolver;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Dev-only security configuration, active when {@code saas.security.dev.enabled=true}. It replaces
 * the production {@link SecurityConfig} (which is conditionally skipped in that case) with a chain
 * that permits all requests — no JWT validation — and provides a {@link TenantResolver} that returns
 * a fixed tenant derived from {@link DevSecurityProperties}.
 *
 * <p>This lets the chat endpoint (and other {@code /api/**} routes) be exercised without a login
 * flow. The {@link io.agentscope.saas.app.chat.SaasChatController} tolerates a {@code null} JWT
 * principal by falling back to this resolver with empty claims.
 */
@Configuration
@EnableConfigurationProperties(DevSecurityProperties.class)
@ConditionalOnProperty(prefix = "saas.security.dev", name = "enabled", havingValue = "true")
public class DevSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(DevSecurityConfig.class);

    @Bean
    public SecurityWebFilterChain devSecurityWebFilterChain(ServerHttpSecurity http) {
        log.warn(
                "DEV AUTH BYPASS IS ACTIVE — /api/** is unauthenticated. "
                        + "Never enable saas.security.dev.enabled in production.");
        http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll());
        return http.build();
    }

    /**
     * Replaces {@link io.agentscope.saas.core.tenant.JwtTenantResolver} in dev mode: ignores claims
     * and returns the configured default tenant, so quota/resource-management paths still see a
     * well-formed {@link TenantContext}.
     */
    @Bean
    @Primary
    public TenantResolver devTenantResolver(DevSecurityProperties properties) {
        TenantContext fixed =
                new TenantContext(
                        properties.getOrgId(),
                        properties.getUserId(),
                        properties.getRole(),
                        properties.getTier(),
                        properties.getMaxSandboxes(),
                        properties.getTokenQuota());
        log.info(
                "Dev tenant resolver active: org={} user={} tier={} maxSandboxes={}",
                fixed.orgId(),
                fixed.userId(),
                fixed.tier(),
                fixed.maxSandboxes());
        return new TenantResolver() {
            @Override
            public TenantContext resolve(Map<String, Object> claims) {
                return fixed;
            }
        };
    }
}
