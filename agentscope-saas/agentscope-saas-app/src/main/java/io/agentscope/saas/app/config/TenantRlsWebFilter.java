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
import reactor.util.context.Context;

/**
 * Writes the authenticated JWT's {@code org_id} claim into the Reactor {@link Context} (key {@link
 * #ORG_ID_KEY}) for the duration of the request, so {@link TenantContextPropagator} can sync it onto
 * the {@link TenantContextHolder} ThreadLocal on every thread that Reactor hops to — including the
 * {@code boundedElastic} workers where repository {@code Mono.fromCallable} calls execute and
 * {@link TenantAwareDataSourceConfig} reads the holder to set the RLS GUC.
 *
 * <p>The org id is carried in the Reactor Context (not the ThreadLocal directly) because the security
 * context resolves on a Reactor {@code parallel-N} scheduler that is neither the Netty request thread
 * nor the eventual {@code boundedElastic} worker. A ThreadLocal set on {@code parallel-N} does not
 * survive the subsequent {@code subscribeOn(boundedElastic)} hop, so the scheduler hook captured
 * {@code null} and RLS denied every authenticated tenant-table write. The Reactor Context flows with
 * the subscription through every operator and scheduler, so {@link TenantContextPropagator}
 * (a {@code Hooks.onEachOperator} hook) re-applies it as a ThreadLocal on whichever thread runs each
 * operator — making the GUC set correctly regardless of the thread topology.
 *
 * <p>Unauthenticated requests (login, register, health, dev bypass) write nothing, so the holder stays
 * empty and RLS denies all tenant rows — those endpoints must use only non-tenant tables (orgs/users
 * by exact email lookup via the admin bypass, tier_policies) or be exempted via the service layer.
 */
@Component
public class TenantRlsWebFilter implements WebFilter {

    /** Reactor Context key carrying the current request's org id (a UUID string, or absent). */
    public static final String ORG_ID_KEY = "saas.orgId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(
                        ctx -> {
                            Authentication auth = ctx.getAuthentication();
                            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                                String orgId = jwt.getClaimAsString("org_id");
                                if (orgId != null && !orgId.isBlank()) {
                                    return orgId;
                                }
                            }
                            return "";
                        })
                .onErrorResume(e -> Mono.just(""))
                .switchIfEmpty(Mono.just(""))
                .flatMap(
                        orgId ->
                                chain.filter(exchange)
                                        .contextWrite(Context.of(ORG_ID_KEY, (Object) orgId)));
    }
}
