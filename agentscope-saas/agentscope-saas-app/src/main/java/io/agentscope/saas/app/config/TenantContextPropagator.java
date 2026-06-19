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
import jakarta.annotation.PostConstruct;
import org.reactivestreams.Subscription;
import org.springframework.stereotype.Component;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;

/**
 * Syncs the request's org id from the Reactor {@link Context} (written by {@link
 * TenantRlsWebFilter}) onto the {@link TenantContextHolder} ThreadLocal on every operator boundary,
 * so {@link TenantAwareDataSourceConfig} sees it on whichever thread issues the DB connection —
 * including the {@code boundedElastic} workers where repository {@code Mono.fromCallable} calls run
 * after a {@code subscribeOn} hop.
 *
 * <p>Replaces the earlier {@code Schedulers.onScheduleHook} bridge. That hook captured the
 * <em>submitting</em> thread's ThreadLocal at schedule time, but the security context resolves on a
 * Reactor {@code parallel-N} scheduler and the {@code subscribeOn(boundedElastic)} re-schedules from
 * a thread that no longer carried the holder, so the hook captured {@code null} and RLS denied every
 * authenticated tenant-table write. The Reactor Context flows with the subscription through every
 * operator and scheduler, so an {@code onEachOperator} hook reading the Context is correct regardless
 * of thread topology.
 *
 * <p>The hook reads the {@link TenantRlsWebFilter#ORG_ID_KEY} entry from the downstream subscriber's
 * Context. An empty string (the filter's value for an unauthenticated request) clears the holder,
 * preserving the "deny all tenant rows" safe default. The ThreadLocal is re-applied before every
 * signal delivered to the downstream subscriber, so it is always current for the thread about to run
 * the next operator (e.g. the {@code fromCallable} body that checks out the connection).
 */
@Component
public class TenantContextPropagator {

    private static final String HOOK_KEY = "saas-tenant-context";

    @PostConstruct
    public void install() {
        Hooks.onEachOperator(
                HOOK_KEY,
                Operators.lift(
                        (scannable, downstream) -> new TenantContextSubscriber<>(downstream)));
    }

    private static final class TenantContextSubscriber<T> implements CoreSubscriber<T> {
        private final CoreSubscriber<? super T> downstream;
        private final String orgId;

        TenantContextSubscriber(CoreSubscriber<? super T> downstream) {
            this.downstream = downstream;
            Object v =
                    downstream.currentContext().getOrDefault(TenantRlsWebFilter.ORG_ID_KEY, null);
            this.orgId = (v instanceof String s && !s.isBlank()) ? s : null;
        }

        @Override
        public Context currentContext() {
            return downstream.currentContext();
        }

        @Override
        public void onSubscribe(Subscription s) {
            apply();
            downstream.onSubscribe(s);
        }

        @Override
        public void onNext(T item) {
            apply();
            downstream.onNext(item);
        }

        @Override
        public void onError(Throwable t) {
            apply();
            downstream.onError(t);
        }

        @Override
        public void onComplete() {
            apply();
            downstream.onComplete();
        }

        private void apply() {
            TenantContextHolder.setOrgId(orgId);
        }
    }
}
