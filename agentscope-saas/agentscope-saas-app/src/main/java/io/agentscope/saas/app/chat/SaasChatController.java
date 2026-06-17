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
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
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
 */
@RestController
@RequestMapping("/api/chat")
public class SaasChatController {

    private static final Logger log = LoggerFactory.getLogger(SaasChatController.class);

    /** Chat request payload. */
    public record ChatRequest(String agentId, String sessionId, String message) {}

    private final HarnessAgent agent;
    private final TenantResolver tenantResolver;
    private final AguiEventEncoder encoder = new AguiEventEncoder();

    public SaasChatController(HarnessAgent agent, TenantResolver tenantResolver) {
        this.agent = agent;
        this.tenantResolver = tenantResolver;
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
        String sessionId =
                request.sessionId() != null && !request.sessionId().isBlank()
                        ? request.sessionId()
                        : UUID.randomUUID().toString();
        String threadId = sessionId;
        String runId = UUID.randomUUID().toString();

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

        AguiEventConverter converter = new AguiEventConverter(threadId, runId);

        log.debug(
                "Chat stream org={} user={} session={} agent={}",
                tenant.orgId(),
                tenant.userId(),
                sessionId,
                request.agentId());

        Flux<AguiEvent> aguiEvents =
                Flux.concat(
                        Flux.just(converter.runStarted()),
                        Mono.fromCallable(() -> ctx)
                                .flatMapMany(c -> agent.streamEvents(List.of(userMsg), c))
                                .subscribeOn(Schedulers.boundedElastic())
                                .concatMapIterable(converter::convert),
                        Flux.defer(() -> Flux.fromIterable(converter.runFinished())));

        return aguiEvents
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
}
