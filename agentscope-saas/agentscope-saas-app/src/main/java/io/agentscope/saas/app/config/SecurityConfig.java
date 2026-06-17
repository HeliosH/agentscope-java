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

import io.agentscope.saas.app.auth.JwtProperties;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoders;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Reactive security configuration. Validates bearer JWTs on {@code /api/**} (except the public auth
 * and health endpoints) and serves the SPA / static assets openly.
 *
 * <p>The {@link ReactiveJwtDecoder} is chosen by configuration: an external IdP issuer/JWK set
 * (enterprise SSO) when configured, otherwise a local symmetric (HS256) decoder matching the tokens
 * minted by {@link io.agentscope.saas.app.auth.JwtService}.
 *
 * <p>The {@code securityWebFilterChain} and {@code reactiveJwtDecoder} beans are skipped when
 * {@code saas.security.dev.enabled=true}; {@link DevSecurityConfig} then provides a permit-all chain.
 * The {@code PasswordEncoder} and {@link JwtProperties} beans stay always-on so {@code AuthController}
 * / {@code JwtService} can wire regardless of mode.
 */
@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    @Bean
    @ConditionalOnProperty(
            prefix = "saas.security.dev",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true)
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(
                        exchanges ->
                                exchanges
                                        .pathMatchers(
                                                "/api/auth/login",
                                                "/api/auth/sso/**",
                                                "/actuator/health",
                                                "/actuator/info")
                                        .permitAll()
                                        .pathMatchers("/api/**")
                                        .authenticated()
                                        // SPA + static assets are public; the app guards data via
                                        // the API layer.
                                        .anyExchange()
                                        .permitAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "saas.security.dev",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true)
    public ReactiveJwtDecoder reactiveJwtDecoder(JwtProperties properties) {
        if (properties.getJwkSetUri() != null && !properties.getJwkSetUri().isBlank()) {
            return NimbusReactiveJwtDecoder.withJwkSetUri(properties.getJwkSetUri()).build();
        }
        if (properties.getIssuerUri() != null && !properties.getIssuerUri().isBlank()) {
            return ReactiveJwtDecoders.fromIssuerLocation(properties.getIssuerUri());
        }
        // Local symmetric (HS256) decoder for the self-issued tokens.
        SecretKey key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
        return NimbusReactiveJwtDecoder.withSecretKey(key).build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
