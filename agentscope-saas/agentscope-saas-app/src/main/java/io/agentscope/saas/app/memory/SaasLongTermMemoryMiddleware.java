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
package io.agentscope.saas.app.memory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.memory.mem0.Mem0AddRequest;
import io.agentscope.core.memory.mem0.Mem0ApiType;
import io.agentscope.core.memory.mem0.Mem0Client;
import io.agentscope.core.memory.mem0.Mem0Message;
import io.agentscope.core.memory.mem0.Mem0SearchRequest;
import io.agentscope.core.memory.mem0.Mem0SearchResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.saas.core.tenant.TenantContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * SaaS long-term memory middleware: retrieves semantically relevant memories from Mem0 before each
 * agent call and records the conversation after the call completes, scoped per-tenant by user id
 * and org id.
 *
 * <p>This is the application-layer replacement for the framework's deprecated {@code
 * StaticLongTermMemoryHook}/{@code LongTermMemory} API (both {@code @Deprecated forRemoval} in
 * 2.0). It uses {@link Mem0Client} and the Mem0 request/response types directly — not the
 * deprecated {@code LongTermMemory} interface — so it remains valid after the 2.0 removal.
 *
 * <h2>Multi-tenant isolation</h2>
 *
 * The SaaS agent is a singleton serving all tenants, so the middleware holds a single shared {@link
 * Mem0Client} (which owns one underlying OkHttpClient/connection pool) and builds a per-call
 * {@link Mem0AddRequest}/{@link Mem0SearchRequest} carrying the caller's {@code userId} (as Mem0
 * {@code user_id}) and {@code orgId} (as a metadata filter). Mem0 only returns memories whose
 * metadata matches, so Alice's memories are invisible to Bob even if user ids collided across
 * orgs.
 *
 * <h2>Flow</h2>
 *
 * <ul>
 *   <li><b>Pre-call</b> (before {@code next.apply}): take the last user message as the query, call
 *       {@code mem0.search}, and if results come back, append a {@code <long_term_memory>} user
 *       message to the input so the model sees recalled context. Retrieval errors are logged and
 *       swallowed — a Mem0 outage never breaks the chat.
 *   <li><b>Post-call</b> (in {@code doFinally}): record the input messages to Mem0 asynchronously
 *       on {@code boundedElastic} so the response stream is never blocked. Record errors are
 *       logged and swallowed.
 * </ul>
 *
 * <p>When Mem0 is not configured ({@code saas.ltm.enabled=false}), this middleware is not wired
 * and the agent falls back to the existing MEMORY.md + snapshot persistence.
 */
public class SaasLongTermMemoryMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SaasLongTermMemoryMiddleware.class);

    /** Mem0 metadata key carrying the org id, used as a retrieval filter for tenant isolation. */
    private static final String ORG_ID_META_KEY = "org_id";

    private final Mem0Client mem0Client;
    private final String agentName;
    private final int topK;

    /**
     * @param mem0Client shared Mem0 client (owns the underlying HTTP client; reuse one instance)
     * @param agentName agent identifier stored with each memory ({@code agent_id})
     * @param topK max memories to retrieve per call
     */
    public SaasLongTermMemoryMiddleware(Mem0Client mem0Client, String agentName, int topK) {
        this.mem0Client = Objects.requireNonNull(mem0Client, "mem0Client");
        this.agentName = agentName;
        this.topK = topK > 0 ? topK : 5;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {

        TenantContext tc = TenantContext.from(ctx);
        // No tenant context (e.g. anonymous/dev bypass) -> skip LTM, run the agent unchanged.
        if (tc == null || tc.userId() == null || tc.userId().isBlank()) {
            return next.apply(input);
        }

        Map<String, Object> filters = new HashMap<>();
        filters.put(ORG_ID_META_KEY, tc.orgId());

        List<Msg> originalMsgs = input.msgs();
        Msg query = lastUserMessage(originalMsgs);

        // Pre-call retrieval: enhance the input with recalled memories when available.
        Mono<AgentInput> enhancedInput =
                (query == null)
                        ? Mono.just(input)
                        : retrieveMemories(query, tc.userId(), filters)
                                .map(
                                        memoryText ->
                                                appendMemoryMessage(
                                                        input, originalMsgs, memoryText))
                                .onErrorResume(
                                        e -> {
                                            log.warn(
                                                    "LTM retrieve failed for user {}: {}",
                                                    tc.userId(),
                                                    e.getMessage());
                                            return Mono.just(input);
                                        })
                                .defaultIfEmpty(input);

        return enhancedInput
                .flatMapMany(next::apply)
                .doFinally(
                        signal ->
                                recordConversation(
                                        originalMsgs, tc.userId(), filters, ctx.getSessionId()));
    }

    /** Searches Mem0 for memories relevant to the query, returning the joined memory text. */
    private Mono<String> retrieveMemories(Msg query, String userId, Map<String, Object> filters) {
        String queryText = query.getTextContent();
        if (queryText == null || queryText.isEmpty()) {
            return Mono.empty();
        }
        Mem0SearchRequest request =
                Mem0SearchRequest.builder()
                        .query(queryText)
                        .userId(userId)
                        .agentId(agentName)
                        .filters(filters)
                        .topK(topK)
                        .build();
        return mem0Client
                .search(request)
                .map(
                        response -> {
                            if (response.getResults() == null || response.getResults().isEmpty()) {
                                return "";
                            }
                            return response.getResults().stream()
                                    .map(Mem0SearchResult::getMemory)
                                    .filter(Objects::nonNull)
                                    .filter(m -> !m.isEmpty())
                                    .collect(Collectors.joining("\n"));
                        })
                .filter(text -> !text.isEmpty());
    }

    /** Appends a user message wrapping the recalled memories so the model sees them as context. */
    private static AgentInput appendMemoryMessage(
            AgentInput input, List<Msg> originalMsgs, String memoryText) {
        Msg memoryMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("long_term_memory")
                        .content(TextBlock.builder().text(wrap(memoryText)).build())
                        .build();
        List<Msg> enhanced = new ArrayList<>(originalMsgs);
        enhanced.add(memoryMsg);
        return new AgentInput(enhanced);
    }

    /** Asynchronously records the conversation messages to Mem0 (fire-and-forget). */
    private void recordConversation(
            List<Msg> msgs, String userId, Map<String, Object> filters, String sessionId) {
        List<Mem0Message> mem0Messages = toMem0Messages(msgs);
        if (mem0Messages.isEmpty()) {
            return;
        }
        Mem0AddRequest request =
                Mem0AddRequest.builder()
                        .messages(mem0Messages)
                        .agentId(agentName)
                        .userId(userId)
                        .runId(sessionId)
                        .metadata(filters)
                        .infer(true)
                        .build();
        mem0Client
                .add(request)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(
                        e -> log.warn("LTM record failed for user {}: {}", userId, e.getMessage()))
                .onErrorComplete()
                .subscribe();
    }

    /** Converts agent messages to Mem0 messages, filtering out empty/compressed content. */
    private static List<Mem0Message> toMem0Messages(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return List.of();
        }
        List<Mem0Message> out = new ArrayList<>();
        for (Msg msg : msgs) {
            if (msg == null) {
                continue;
            }
            String text = msg.getTextContent();
            if (text == null || text.isEmpty() || text.contains("<compressed_history>")) {
                continue;
            }
            String role =
                    switch (msg.getRole()) {
                        case USER, SYSTEM -> "user";
                        case ASSISTANT, TOOL -> "assistant";
                    };
            out.add(Mem0Message.builder().role(role).content(text).build());
        }
        return out;
    }

    private static Msg lastUserMessage(List<Msg> msgs) {
        if (msgs == null) {
            return null;
        }
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).getRole() == MsgRole.USER) {
                return msgs.get(i);
            }
        }
        return null;
    }

    /**
     * Wraps retrieved memory text in a marker block so the model can identify it. Inlined from the
     * deprecated {@code LongTermMemoryTools.wrap} to avoid referencing a forRemoval class.
     */
    private static String wrap(String text) {
        return "Below is content retrieved from the long-term memory associated with the current"
                + " user. Extract useful information from it in the context of the current"
                + " conversation.\n"
                + "<long_term_memory>\n"
                + text
                + "\n</long_term_memory>";
    }

    /**
     * Builds the shared {@link Mem0Client} from the SaaS LTM configuration. Exposed as a static
     * factory so {@code AgentConfig} can construct the singleton client without depending on Mem0
     * builder details.
     *
     * @param apiBaseUrl Mem0 base URL (required)
     * @param apiKey Mem0 API key (optional for self-hosted without auth)
     * @param apiType {@code PLATFORM} or {@code SELF_HOSTED}
     * @param timeoutSeconds request timeout
     */
    public static Mem0Client createClient(
            String apiBaseUrl, String apiKey, String apiType, int timeoutSeconds) {
        Mem0ApiType type =
                "SELF_HOSTED".equalsIgnoreCase(apiType)
                        ? Mem0ApiType.SELF_HOSTED
                        : Mem0ApiType.PLATFORM;
        Duration timeout = Duration.ofSeconds(timeoutSeconds > 0 ? timeoutSeconds : 60);
        return new Mem0Client(apiBaseUrl, apiKey, type, timeout);
    }
}
