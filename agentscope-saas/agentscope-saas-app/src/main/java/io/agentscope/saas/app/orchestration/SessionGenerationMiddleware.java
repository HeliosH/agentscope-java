/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.orchestration;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.state.AgentStateNamespace;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Cross-replica revocation cancels the invocation, not just its next model/tool preflight. */
@Component
public class SessionGenerationMiddleware implements MiddlewareBase {
    private final SessionRunFenceService fences;
    private final Duration interval;

    public SessionGenerationMiddleware(SessionRunFenceService fences, SaasProperties properties) {
        this.fences = fences;
        long millis = properties.getOrchestration().getSessionFencePollMillis();
        if (millis < 100 || millis > 60000)
            throw new IllegalArgumentException("Invalid session fence poll interval");
        interval = Duration.ofMillis(millis);
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext context,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(
                () -> {
                    var binding = fences.bind(context);
                    if (binding == null) return next.apply(input);
                    context.put(SessionRunFenceService.Binding.class, binding);
                    context.put(
                            AgentStateNamespace.class,
                            binding.fence().generation() == 0
                                    ? null
                                    : new AgentStateNamespace(
                                            "generation-" + binding.fence().generation()));
                    var reason = new AtomicReference<Throwable>();
                    Mono<Throwable> revoked =
                            Flux.interval(interval)
                                    .onBackpressureLatest()
                                    .concatMap(
                                            tick ->
                                                    Mono.fromCallable(() -> fences.current(binding))
                                                            .subscribeOn(
                                                                    Schedulers.boundedElastic()),
                                            1)
                                    .filter(current -> !current)
                                    .map(
                                            ignored ->
                                                    (Throwable)
                                                            new SessionExecutionRevokedException())
                                    .next()
                                    .onErrorResume(error -> Mono.just(error))
                                    .doOnNext(reason::set);
                    // A companion error alone does not cancel the subscribed main publisher.
                    return next.apply(input)
                            .takeUntilOther(revoked)
                            .concatWith(
                                    Flux.<AgentEvent>defer(
                                            () ->
                                                    reason.get() == null
                                                            ? Flux.empty()
                                                            : Flux.error(reason.get())));
                });
    }
}
