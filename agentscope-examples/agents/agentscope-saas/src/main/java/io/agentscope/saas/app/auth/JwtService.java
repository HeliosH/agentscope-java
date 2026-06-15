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

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues HS256 JWTs for the local-login path. Tokens carry the {@code org_id}, {@code user_id},
 * {@code role}, and {@code tier} claims that {@link io.agentscope.saas.core.tenant.JwtTenantResolver}
 * reads to build the tenant context. When SSO is enabled, tokens are issued by the external IdP
 * instead and this service is unused for issuance.
 */
@Service
public class JwtService {

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /** Issue a signed token for an authenticated user. */
    public String issue(String userId, String orgId, String email, String role, String tier) {
        long now = System.currentTimeMillis();
        Date issuedAt = new Date(now);
        Date expiry = new Date(now + properties.getTtlSeconds() * 1000L);
        return Jwts.builder()
                .issuer(properties.getIssuer())
                .subject(userId)
                .issuedAt(issuedAt)
                .expiration(expiry)
                .claim("org_id", orgId)
                .claim("user_id", userId)
                .claim("email", email == null ? "" : email)
                .claim("role", role == null ? "member" : role)
                .claim("tier", tier == null ? "standard" : tier)
                .signWith(key)
                .compact();
    }

    public SecretKey getSigningKey() {
        return key;
    }
}
