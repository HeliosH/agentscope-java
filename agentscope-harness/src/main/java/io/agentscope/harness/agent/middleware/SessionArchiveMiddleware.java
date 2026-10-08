/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.ConversationCommitter;
import io.agentscope.harness.agent.memory.session.SessionArchiveStore;
import java.util.List;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Commits sources before working projections, recovery checkpoints and terminal cleanup. */
public class SessionArchiveMiddleware implements MiddlewareBase {
    private final SessionArchiveStore archive;

    public SessionArchiveMiddleware(SessionArchiveStore archive) {
        this.archive = archive;
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext context,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return commit(agent, context, input.messages())
                .thenMany(Flux.defer(() -> next.apply(input)));
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext context,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return commit(agent, context, input.msgs())
                .thenMany(
                        Flux.defer(
                                () -> {
                                    context.put(ConversationCommitter.class, this::commitSync);
                                    return Flux.defer(() -> next.apply(input))
                                            .concatWith(
                                                    commitState(agent, context)
                                                            .thenMany(Flux.<AgentEvent>empty()))
                                            .onErrorResume(
                                                    error -> {
                                                        if (error
                                                                instanceof
                                                                SessionArchiveStore
                                                                        .ArchiveCommitException)
                                                            return Flux.error(error);
                                                        return commitState(agent, context)
                                                                .onErrorMap(
                                                                        failure -> {
                                                                            if (failure != error)
                                                                                failure
                                                                                        .addSuppressed(
                                                                                                error);
                                                                            return failure;
                                                                        })
                                                                .thenMany(Flux.error(error));
                                                    });
                                }));
    }

    private Mono<Void> commitState(Agent agent, RuntimeContext context) {
        return Mono.fromRunnable(
                        () -> ConversationCommitter.commitBoundary(context, agent.getName()))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private void commitSync(RuntimeContext context, String name, List<Msg> messages) {
        try {
            archive.append(context, name, context.getSessionId(), List.copyOf(messages));
        } catch (RuntimeException error) {
            if (error instanceof SessionArchiveStore.ArchiveCommitException) throw error;
            throw new SessionArchiveStore.ArchiveCommitException(error);
        }
    }

    private Mono<Void> commit(Agent agent, RuntimeContext context, List<Msg> messages) {
        return Mono.fromRunnable(() -> commitSync(context, agent.getName(), messages))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
