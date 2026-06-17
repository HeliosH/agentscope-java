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
package io.agentscope.saas.app.chat;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.saas.core.persistence.entity.AgentEntity;
import io.agentscope.saas.core.persistence.entity.ChatSessionEntity;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantResolver;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * AG-UI compatible streaming chat endpoint. Accepts a user message, injects the authenticated
 * tenant context into the agent {@link RuntimeContext}, runs the agent via
 * {@code streamEvents(msgs, ctx)}, and streams the resulting events back as AG-UI Server-Sent
 * Events. The wire format matches the frontend's {@code agui-stream.ts} consumer.
 *
 * <p>When the tenant ids are real UUIDs (production login), the user message and the final assistant
 * reply are persisted via {@link ChatPersistenceService} so the conversation survives refresh and
 * re-login. In dev auth-bypass mode (non-UUID ids) persistence is skipped and the endpoint behaves
 * as a pure streaming pass-through.
 */
@RestController
@RequestMapping("/api/chat")
public class SaasChatController {

    private static final Logger log = LoggerFactory.getLogger(SaasChatController.class);

    /** Chat request payload. */
    public record ChatRequest(String agentId, String sessionId, String message) {}

    private final HarnessAgent agent;
    private final TenantResolver tenantResolver;
    private final ChatPersistenceService persistence;
    private final AguiEventEncoder encoder = new AguiEventEncoder();

    public SaasChatController(
            HarnessAgent agent, TenantResolver tenantResolver, ChatPersistenceService persistence) {
        this.agent = agent;
        this.tenantResolver = tenantResolver;
        this.persistence = persistence;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @AuthenticationPrincipal Jwt jwt, @RequestBody ChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            return Flux.error(new IllegalArgumentException("message is required"));
        }

        // In dev (auth bypass) the principal is null; the TenantResolver (a DevTenantResolver)
        // returns a fixed tenant for empty claims. In production jwt is always present.
        Map<String, Object> claims = jwt != null ? jwt.getClaims() : Map.of();
        TenantContext tenant = tenantResolver.resolve(claims);
        boolean persistable = isPersistable(tenant);

        if (persistable) {
            return streamingWithPersistence(tenant, request);
        }
        return streamingWithoutPersistence(tenant, request);
    }

    /** Production path: resolve agent/session, persist user + assistant messages around the run. */
    private Flux<ServerSentEvent<String>> streamingWithPersistence(
            TenantContext tenant, ChatRequest request) {
        return Mono.fromCallable(
                        () -> {
                            AgentEntity agentEntity =
                                    persistence.resolveAgent(tenant, request.agentId());
                            ChatSessionEntity session =
                                    persistence.resolveSession(
                                            tenant,
                                            agentEntity.getId(),
                                            request.sessionId(),
                                            request.message());
                            persistence.saveUserMessage(
                                    tenant,
                                    session.getId(),
                                    agentEntity.getId(),
                                    request.message());
                            return new ResolvedRun(agentEntity.getId(), session.getId());
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(resolved -> runAgent(tenant, request, resolved, true));
    }

    /** Dev/bypass path: no persistence, ephemeral session id. */
    private Flux<ServerSentEvent<String>> streamingWithoutPersistence(
            TenantContext tenant, ChatRequest request) {
        String sessionId =
                request.sessionId() != null && !request.sessionId().isBlank()
                        ? request.sessionId()
                        : UUID.randomUUID().toString();
        return runAgent(tenant, request, new ResolvedRun(null, UUID.fromString(sessionId)), false);
    }

    /** Runs the agent and emits AG-UI SSE events; optionally persists the assistant reply. */
    private Flux<ServerSentEvent<String>> runAgent(
            TenantContext tenant, ChatRequest request, ResolvedRun resolved, boolean persist) {
        String sessionId = resolved.sessionId().toString();
        String threadId = sessionId;
        String runId = UUID.randomUUID().toString();
        AguiEventConverter converter = new AguiEventConverter(threadId, runId);
        AssistantTextAccumulator accumulator = new AssistantTextAccumulator();

        RuntimeContext ctx =
                RuntimeContext.builder()
                        .userId(tenant.userId())
                        .sessionId(sessionId)
                        .put(TenantContext.class, tenant)
                        .put(TenantContext.ATTR_KEY, tenant)
                        .build();

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name(tenant.userId() != null ? tenant.userId() : "user")
                        .textContent(request.message())
                        .build();

        log.debug(
                "Chat stream org={} user={} session={} agent={}",
                tenant.orgId(),
                tenant.userId(),
                sessionId,
                resolved.agentId());

        Flux<AguiEvent> agentEvents =
                agent.streamEvents(List.of(userMsg), ctx)
                        .subscribeOn(Schedulers.boundedElastic())
                        .doOnNext(accumulator::onEvent)
                        .concatMapIterable(converter::convert);

        Flux<AguiEvent> withPersistence =
                persist
                        ? Flux.concat(
                                Flux.just(converter.runStarted()),
                                agentEvents,
                                Mono.fromCallable(
                                                () -> {
                                                    persistence.saveAssistantMessage(
                                                            tenant,
                                                            resolved.sessionId(),
                                                            resolved.agentId(),
                                                            accumulator.text());
                                                    return (Object) null;
                                                })
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .flatMapMany(v -> Flux.empty()),
                                Flux.defer(() -> Flux.fromIterable(converter.runFinished())))
                        : Flux.concat(
                                Flux.just(converter.runStarted()),
                                agentEvents,
                                Flux.defer(() -> Flux.fromIterable(converter.runFinished())));

        return withPersistence
                .map(this::toSse)
                .onErrorResume(
                        error -> {
                            log.warn("Chat stream error: {}", error.toString());
                            Map<String, Object> payload = new HashMap<>();
                            payload.put("message", error.getMessage());
                            AguiEvent errorEvent =
                                    new AguiEvent.Custom(threadId, runId, "error", payload);
                            return Flux.just(toSse(errorEvent));
                        })
                .doOnCancel(
                        () -> {
                            log.debug("Client cancelled chat stream; interrupting agent");
                            agent.interrupt();
                        });
    }

    private ServerSentEvent<String> toSse(AguiEvent event) {
        // encodeToJson returns " {json}"; trim the SSE-compatibility leading space — Spring writes
        // the "data:" prefix itself.
        return ServerSentEvent.<String>builder().data(encoder.encodeToJson(event).trim()).build();
    }

    /** Persistence requires UUID-shaped tenant ids (production JWT); dev bypass uses string ids. */
    private static boolean isPersistable(TenantContext tenant) {
        return isUuid(tenant.orgId()) && isUuid(tenant.userId());
    }

    private static boolean isUuid(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Captured agent + session ids for a resolved run. */
    private record ResolvedRun(UUID agentId, UUID sessionId) {}

    /** Accumulates the assistant's streamed text deltas so the final reply can be persisted. */
    private static final class AssistantTextAccumulator {
        private final StringBuilder buffer = new StringBuilder();

        void onEvent(AgentEvent event) {
            if (event instanceof TextBlockDeltaEvent e
                    && e.getDelta() != null
                    && !e.getDelta().isEmpty()) {
                buffer.append(e.getDelta());
            }
        }

        String text() {
            return buffer.toString();
        }
    }
}
