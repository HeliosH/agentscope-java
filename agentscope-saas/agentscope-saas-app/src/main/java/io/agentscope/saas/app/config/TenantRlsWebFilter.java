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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Sets {@link TenantContextHolder} from the authenticated JWT's {@code org_id} claim for the
 * duration of the request, so {@link TenantAwareDataSourceConfig} can scope every DB connection to
 * the tenant for Row-Level Security.
 *
 * <p>Runs on the Netty request thread; the value is bridged onto {@code boundedElastic} worker
 * threads (where repository {@code Mono.fromCallable} calls execute) by {@link
 * TenantRlsSchedulerHook}, which copies the holder across the schedule boundary. Both the set and
 * the clear run on the same thread, and the scheduler hook re-applies on each worker, so no leakage
 * occurs across pooled requests.
 *
 * <p>Unauthenticated requests (login, register, health, dev bypass) leave the holder empty, which
 * RLS treats as "deny all tenant rows" — those endpoints must use only non-tenant tables (orgs/
 * users by exact email lookup, tier_policies) or be exempted via the service layer.
 */
@Component
public class TenantRlsWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(
                        ctx -> {
                            Authentication auth = ctx.getAuthentication();
                            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                                String orgId = jwt.getClaimAsString("org_id");
                                if (orgId != null && !orgId.isBlank()) {
                                    TenantContextHolder.setOrgId(orgId);
                                }
                            }
                            return (Void) null;
                        })
                .onErrorResume(e -> Mono.empty())
                .switchIfEmpty(Mono.<Void>empty())
                .then(chain.filter(exchange))
                .doFinally(s -> TenantContextHolder.clear());
    }
}
