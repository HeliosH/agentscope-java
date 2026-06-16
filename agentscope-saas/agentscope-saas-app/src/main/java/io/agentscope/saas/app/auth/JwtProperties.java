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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Authentication configuration. When {@code issuerUri} (or {@code jwkSetUri}) is set, the app
 * validates JWTs from an external enterprise IdP (OIDC/SAML-fronted) — that is the SSO path. When
 * neither is set, the app issues and validates its own HS256 tokens using {@code secret} (local
 * login fallback, suitable for small deployments and testing).
 */
@ConfigurationProperties(prefix = "saas.security.jwt")
public class JwtProperties {

    /** Symmetric secret for local HS256 token issuance/validation. Must be >= 32 chars. */
    private String secret = "change-me-saas-dev-secret-please-override-in-prod-0123456789";

    /** Token time-to-live in seconds (default 12 hours). */
    private long ttlSeconds = 43200;

    /** Token issuer identifier embedded in issued tokens. */
    private String issuer = "agentscope-saas";

    /** External IdP issuer URI (enables SSO validation when set). */
    private String issuerUri;

    /** External IdP JWK set URI (alternative to issuerUri for SSO validation). */
    private String jwkSetUri;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getIssuerUri() {
        return issuerUri;
    }

    public void setIssuerUri(String issuerUri) {
        this.issuerUri = issuerUri;
    }

    public String getJwkSetUri() {
        return jwkSetUri;
    }

    public void setJwkSetUri(String jwkSetUri) {
        this.jwkSetUri = jwkSetUri;
    }

    public boolean isSsoEnabled() {
        return (issuerUri != null && !issuerUri.isBlank())
                || (jwkSetUri != null && !jwkSetUri.isBlank());
    }
}
