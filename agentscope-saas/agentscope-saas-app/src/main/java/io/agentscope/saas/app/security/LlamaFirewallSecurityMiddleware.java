/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.app.security;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Scans untrusted model input and the complete assistant output at the runtime boundary. */
public class LlamaFirewallSecurityMiddleware implements MiddlewareBase {

    private final LlamaFirewallClient client;

    public LlamaFirewallSecurityMiddleware(LlamaFirewallClient client) {
        this.client = client;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        if (!client.isEnabled()) {
            return next.apply(input);
        }

        // Buffer only when the firewall is enabled so streamed output cannot reach the caller
        // before the complete final response has passed the outbound security check.
        return next.apply(input)
                .collectList()
                .flatMapMany(
                        events -> {
                            Msg result = finalResult(events);
                            if (result == null) {
                                return Flux.fromIterable(events);
                            }
                            String content = extractText(result);
                            if (content.isBlank()) {
                                content = client.serializeRedacted(result.getContent());
                            }
                            return client.scan(
                                            new LlamaFirewallClient.ScanRequest(
                                                    LlamaFirewallClient.Stage.ASSISTANT_OUTPUT,
                                                    LlamaFirewallClient.Role.ASSISTANT,
                                                    content,
                                                    ctx,
                                                    "message:" + result.getId(),
                                                    true,
                                                    Map.of(
                                                            "message_id", result.getId(),
                                                            "message_role",
                                                                    result.getRole().name())))
                                    .flatMapMany(
                                            decision -> {
                                                if (decision.action()
                                                        == LlamaFirewallClient.Action.ALLOW) {
                                                    return Flux.fromIterable(events);
                                                }
                                                return Flux.error(securityException(decision));
                                            });
                        });
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        if (!client.isEnabled()) {
            return next.apply(input);
        }
        Set<String> scannedMessageIds = scannedMessageIds(ctx);
        List<Msg> candidates = pendingUntrusted(input.messages(), scannedMessageIds);
        if (candidates.isEmpty()) {
            return next.apply(input);
        }
        return Flux.fromIterable(candidates)
                .concatMap(candidate -> scanUntrusted(ctx, candidate, scannedMessageIds))
                .thenMany(Flux.defer(() -> next.apply(input)));
    }

    private Mono<Void> scanUntrusted(
            RuntimeContext ctx, Msg candidate, Set<String> scannedMessageIds) {
        String content = extractText(candidate);
        if (content.isBlank()) {
            content = client.serializeRedacted(candidate.getContent());
        }
        LlamaFirewallClient.Stage stage =
                candidate.getRole() == MsgRole.TOOL
                        ? LlamaFirewallClient.Stage.TOOL_RESULT
                        : LlamaFirewallClient.Stage.USER_INPUT;
        LlamaFirewallClient.Role role =
                candidate.getRole() == MsgRole.TOOL
                        ? LlamaFirewallClient.Role.TOOL
                        : LlamaFirewallClient.Role.USER;
        String scannedContent = content;
        return client.scan(
                        new LlamaFirewallClient.ScanRequest(
                                stage,
                                role,
                                scannedContent,
                                ctx,
                                "message:" + candidate.getId(),
                                false,
                                Map.of(
                                        "message_id", candidate.getId(),
                                        "message_role", candidate.getRole().name())))
                .flatMap(
                        decision -> {
                            if (decision.action() == LlamaFirewallClient.Action.ALLOW) {
                                scannedMessageIds.add(candidate.getId());
                                return Mono.empty();
                            }
                            boolean reviewRequired =
                                    decision.action() == LlamaFirewallClient.Action.ASK;
                            return Mono.error(securityException(decision, reviewRequired));
                        });
    }

    private static Msg finalResult(List<AgentEvent> events) {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i) instanceof AgentResultEvent resultEvent) {
                return resultEvent.getResult();
            }
        }
        return null;
    }

    private static LlamaFirewallSecurityException securityException(
            LlamaFirewallClient.ScanDecision decision) {
        return securityException(decision, decision.action() == LlamaFirewallClient.Action.ASK);
    }

    private static LlamaFirewallSecurityException securityException(
            LlamaFirewallClient.ScanDecision decision, boolean reviewRequired) {
        return new LlamaFirewallSecurityException(
                decision.reason()
                        + " [scanner="
                        + decision.scanner()
                        + ", score="
                        + decision.score()
                        + "]",
                reviewRequired);
    }

    private static List<Msg> pendingUntrusted(List<Msg> messages, Set<String> scannedMessageIds) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int boundary = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg message = messages.get(i);
            if (message != null && message.getRole() == MsgRole.ASSISTANT) {
                boundary = i;
                break;
            }
        }
        List<Msg> pending = new ArrayList<>();
        for (int i = boundary + 1; i < messages.size(); i++) {
            Msg message = messages.get(i);
            if (message != null
                    && (message.getRole() == MsgRole.USER || message.getRole() == MsgRole.TOOL)
                    && !scannedMessageIds.contains(message.getId())) {
                pending.add(message);
            }
        }
        return pending;
    }

    private static Set<String> scannedMessageIds(RuntimeContext ctx) {
        ScannedMessageIds holder = ctx.get(ScannedMessageIds.class);
        if (holder == null) {
            synchronized (ctx) {
                holder = ctx.get(ScannedMessageIds.class);
                if (holder == null) {
                    holder = new ScannedMessageIds();
                    ctx.put(ScannedMessageIds.class, holder);
                }
            }
        }
        return holder.ids;
    }

    private static String extractText(Msg message) {
        List<String> parts = new ArrayList<>();
        collectText(message.getContent(), parts);
        return String.join("\n", parts);
    }

    private static void collectText(List<ContentBlock> blocks, List<String> parts) {
        if (blocks == null) {
            return;
        }
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock text && !text.getText().isBlank()) {
                parts.add(text.getText());
            } else if (block instanceof ToolResultBlock result) {
                collectText(result.getOutput(), parts);
            }
        }
    }

    private static final class ScannedMessageIds {
        private final Set<String> ids = Collections.newSetFromMap(new ConcurrentHashMap<>());
    }
}
