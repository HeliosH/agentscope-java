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
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.degradation.DegradationManager;
import io.agentscope.saas.app.observability.AgentRunMetrics;
import io.agentscope.saas.app.workspace.WorkspaceProjectionCatalogSink;
import io.agentscope.saas.core.persistence.entity.AgentEntity;
import io.agentscope.saas.core.persistence.entity.ChatSessionEntity;
import io.agentscope.saas.core.persistence.repo.ChatSessionRepository;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantResolver;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
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
public class SaasChatController {

    private static final Logger log = LoggerFactory.getLogger(SaasChatController.class);

    /** Chat request payload. {@code confirmResults} resumes a paused HITL run (same sessionId). */
    public record ChatRequest(
            String sessionId,
            String requestId,
            String message,
            List<ConfirmResultInput> confirmResults) {

        /** A single user confirmation decision for a pending tool call (HITL resume). */
        public record ConfirmResultInput(
                boolean confirmed, String toolCallId, String toolName, Map<String, Object> input) {}
    }

    private final HarnessAgent agent;
    private final TenantResolver tenantResolver;
    private final ChatPersistenceService persistence;
    private final ChatRunStartService runStarter;
    private final ChatSessionRepository sessionRepository;
    private final DegradationManager degradationManager;
    private final AgentRunMetrics metrics;
    private final RunOrchestrationService orchestration;
    private final boolean orchestrationEnabled;
    private final String sandboxType;
    private final AguiEventEncoder encoder = new AguiEventEncoder();

    public SaasChatController(
            HarnessAgent agent,
            TenantResolver tenantResolver,
            ChatPersistenceService persistence,
            ChatRunStartService runStarter,
            ChatSessionRepository sessionRepository,
            DegradationManager degradationManager,
            AgentRunMetrics metrics,
            RunOrchestrationService orchestration,
            SaasProperties properties) {
        this.agent = agent;
        this.tenantResolver = tenantResolver;
        this.persistence = persistence;
        this.runStarter = runStarter;
        this.sessionRepository = sessionRepository;
        this.degradationManager = degradationManager;
        this.metrics = metrics != null ? metrics : AgentRunMetrics.noop();
        this.orchestration = orchestration;
        this.orchestrationEnabled =
                properties != null
                        && properties.getOrchestration() != null
                        && properties.getOrchestration().isEnabled();
        this.sandboxType =
                properties != null && properties.getSandbox() != null
                        ? properties.getSandbox().getType()
                        : "unknown";
    }

    /**
     * Returns the caller's most-recent session for the agent, if any. paw's chat page calls this on
     * mount to decide whether to fetch turns. {@code sessionKey} equals the session UUID string.
     */
    public record CurrentSessionResponse(String sessionKey, boolean exists) {}

    /** Explicit cancellation response. Browser/SSE disconnects do not call this endpoint. */
    public record CancelRunResponse(String runId, String status, boolean interrupted) {}

    @GetMapping("/api/agents/{agentId}/chat/session")
    public Mono<CurrentSessionResponse> currentSession(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String agentId) {
        Map<String, Object> claims = jwt != null ? jwt.getClaims() : Map.of();
        TenantContext tenant = tenantResolver.resolve(claims);
        if (!isUuid(tenant.orgId()) || !isUuid(tenant.userId())) {
            return Mono.just(new CurrentSessionResponse(null, false));
        }
        UUID orgId = UUID.fromString(tenant.orgId());
        UUID userId = UUID.fromString(tenant.userId());
        UUID agentUuid;
        try {
            agentUuid = UUID.fromString(agentId);
        } catch (IllegalArgumentException e) {
            return Mono.just(new CurrentSessionResponse(null, false));
        }
        return Mono.fromCallable(
                        () ->
                                sessionRepository
                                        .findFirstByOrgIdAndUserIdAndAgentIdOrderByUpdatedAtDesc(
                                                orgId, userId, agentUuid)
                                        .map(
                                                s ->
                                                        new CurrentSessionResponse(
                                                                s.getId().toString(), true))
                                        .orElseGet(() -> new CurrentSessionResponse(null, false)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping(
            value = "/api/agents/{agentId}/chat/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String agentId,
            @RequestBody ChatRequest request) {
        boolean hasConfirm =
                request != null
                        && request.confirmResults() != null
                        && !request.confirmResults().isEmpty();
        if (request == null
                || ((request.message() == null || request.message().isBlank()) && !hasConfirm)) {
            return Flux.error(new IllegalArgumentException("message is required"));
        }

        // In dev (auth bypass) the principal is null; the TenantResolver (a DevTenantResolver)
        // returns a fixed tenant for empty claims. In production jwt is always present.
        Map<String, Object> claims = jwt != null ? jwt.getClaims() : Map.of();
        TenantContext tenant = tenantResolver.resolve(claims);
        boolean persistable = isPersistable(tenant);
        DegradationManager.Decision decision = degradationManager.evaluateChat();
        if (!decision.allowed()) {
            return degradationBlockedStream(request, decision, persistable);
        }

        if (persistable) {
            return streamingWithPersistence(tenant, agentId, request);
        }
        return streamingWithoutPersistence(tenant, agentId, request);
    }

    /** Production path: resolve agent/session, persist user + assistant messages around the run. */
    private Flux<ServerSentEvent<String>> streamingWithPersistence(
            TenantContext tenant, String agentId, ChatRequest request) {
        return Mono.fromCallable(
                        () -> {
                            String message =
                                    request.message() != null && !request.message().isBlank()
                                            ? request.message()
                                            : "[tool confirmation]";
                            if (orchestrationEnabled) {
                                ChatRunStartService.StartedRun started =
                                        runStarter.start(
                                                tenant,
                                                agentId,
                                                request.sessionId(),
                                                message,
                                                request.requestId());
                                return new ResolvedRun(
                                        started.agentId(),
                                        started.sessionId(),
                                        started.triggerMessageId(),
                                        started.runId(),
                                        started.rootAgentRunId(),
                                        started.status(),
                                        started.reused());
                            }
                            AgentEntity agentEntity = persistence.resolveAgent(tenant, agentId);
                            ChatSessionEntity session =
                                    persistence.resolveSession(
                                            tenant,
                                            agentEntity.getId(),
                                            request.sessionId(),
                                            message);
                            var userMessage =
                                    persistence.saveUserMessage(
                                            tenant, session.getId(), agentEntity.getId(), message);
                            return new ResolvedRun(
                                    agentEntity.getId(),
                                    session.getId(),
                                    userMessage.getId(),
                                    null,
                                    null,
                                    null,
                                    false);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(resolved -> runAgent(tenant, request, resolved, true));
    }

    /** Dev/bypass path: no persistence, ephemeral session id. */
    private Flux<ServerSentEvent<String>> streamingWithoutPersistence(
            TenantContext tenant, String agentId, ChatRequest request) {
        String sessionId =
                request.sessionId() != null && !request.sessionId().isBlank()
                        ? request.sessionId()
                        : UUID.randomUUID().toString();
        return runAgent(
                tenant,
                request,
                new ResolvedRun(null, UUID.fromString(sessionId), null, null, null, null, false),
                false);
    }

    /** Runs the agent and emits AG-UI SSE events; optionally persists the assistant reply. */
    private Flux<ServerSentEvent<String>> runAgent(
            TenantContext tenant, ChatRequest request, ResolvedRun resolved, boolean persist) {
        String sessionId = resolved.sessionId().toString();
        String threadId = sessionId;
        UUID durableRunId = persist && orchestrationEnabled ? resolved.runId() : UUID.randomUUID();
        String runId = durableRunId.toString();
        AguiEventConverter converter = new AguiEventConverter(threadId, runId);
        if (persist && orchestrationEnabled && resolved.reused()) {
            return reusedRunStream(converter, threadId, runId, resolved.status());
        }
        AssistantContentAccumulator accumulator = new AssistantContentAccumulator();
        long startedNanos = System.nanoTime();
        AtomicReference<String> streamOutcome = new AtomicReference<>("success");

        RuntimeContext ctx =
                RuntimeContext.builder()
                        .userId(tenant.userId())
                        .sessionId(sessionId)
                        .put(
                                WorkspaceProjectionCatalogSink.ATTR_AGENT_ID,
                                resolved.agentId() != null ? resolved.agentId().toString() : null)
                        .put(RunOrchestrationService.ATTR_RUN_ID, runId)
                        .put(
                                RunOrchestrationService.ATTR_AGENT_RUN_ID,
                                resolved.rootAgentRunId() != null
                                        ? resolved.rootAgentRunId().toString()
                                        : null)
                        .put(TenantContext.class, tenant)
                        .put(TenantContext.ATTR_KEY, tenant)
                        .build();

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name(tenant.userId() != null ? tenant.userId() : "user")
                        .textContent(request.message() != null ? request.message() : "")
                        .metadata(buildMetadata(request))
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
                                                            accumulator.blocks());
                                                    if (orchestrationEnabled) {
                                                        orchestration.markSucceeded(
                                                                tenant,
                                                                resolved.agentId(),
                                                                durableRunId);
                                                    }
                                                    return (Object) null;
                                                })
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .flatMapMany(v -> Flux.empty()),
                                Flux.defer(() -> Flux.fromIterable(converter.runFinished())))
                        : Flux.concat(
                                Flux.just(converter.runStarted()),
                                agentEvents,
                                Flux.defer(() -> Flux.fromIterable(converter.runFinished())));

        Flux<ServerSentEvent<String>> execution =
                withPersistence
                        .map(this::toSse)
                        .onErrorResume(
                                error -> {
                                    streamOutcome.set("error");
                                    log.warn("Chat stream error: {}", error.toString());
                                    Map<String, Object> payload = new HashMap<>();
                                    payload.put("message", errorMessage(error));
                                    AguiEvent errorEvent =
                                            new AguiEvent.Custom(threadId, runId, "error", payload);
                                    if (!persist || !orchestrationEnabled) {
                                        return Flux.just(toSse(errorEvent));
                                    }
                                    return Mono.fromRunnable(
                                                    () ->
                                                            orchestration.markFailed(
                                                                    tenant,
                                                                    resolved.agentId(),
                                                                    durableRunId,
                                                                    "AGENT_ERROR",
                                                                    errorMessage(error)))
                                            .subscribeOn(Schedulers.boundedElastic())
                                            .thenMany(Flux.just(toSse(errorEvent)));
                                })
                        .doFinally(
                                signal -> {
                                    metrics.recordChatStream(
                                            streamOutcome.get(),
                                            persist,
                                            sandboxType,
                                            System.nanoTime() - startedNanos);
                                });
        return detachClient(execution, runId);
    }

    private Flux<ServerSentEvent<String>> reusedRunStream(
            AguiEventConverter converter, String threadId, String runId, String status) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", status);
        payload.put("reused", true);
        Flux<AguiEvent> events =
                Flux.just(
                        converter.runStarted(),
                        new AguiEvent.Custom(threadId, runId, "run_reused", payload));
        if (!RunOrchestrationService.RUN_RUNNING.equals(status)) {
            events = Flux.concat(events, Flux.fromIterable(converter.runFinished()));
        }
        return events.map(this::toSse);
    }

    /**
     * Starts the execution with an internal subscription so an HTTP/SSE disconnect only detaches the
     * viewer. The durable Run/Event records remain the cross-restart source of truth; this bounded
     * replay sink only bridges the initially connected browser to its live AG-UI events.
     */
    private Flux<ServerSentEvent<String>> detachClient(
            Flux<ServerSentEvent<String>> execution, String runId) {
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().replay().limit(256);
        execution.subscribe(
                event -> {
                    Sinks.EmitResult result = sink.tryEmitNext(event);
                    if (result.isFailure() && result != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
                        log.debug("Could not relay live SSE event for Run {}: {}", runId, result);
                    }
                },
                error -> sink.tryEmitError(error),
                sink::tryEmitComplete);
        return sink.asFlux();
    }

    @PostMapping("/api/agents/{agentId}/runs/{runId}/cancel")
    public Mono<CancelRunResponse> cancelRun(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String agentId,
            @PathVariable String runId) {
        if (!orchestrationEnabled) {
            return Mono.error(
                    new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "Run orchestration is disabled"));
        }
        TenantContext tenant = tenantResolver.resolve(jwt != null ? jwt.getClaims() : Map.of());
        if (!isPersistable(tenant)) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        }
        return Mono.fromCallable(
                        () -> {
                            UUID agentUuid = parseUuid(agentId, "agentId");
                            UUID runUuid = parseUuid(runId, "runId");
                            RunOrchestrationService.CancelledRun cancelled =
                                    orchestration
                                            .cancel(tenant, agentUuid, runUuid)
                                            .orElseThrow(
                                                    () ->
                                                            new ResponseStatusException(
                                                                    HttpStatus.NOT_FOUND,
                                                                    "Run not found"));
                            if (cancelled.interrupted()) {
                                agent.getDelegate()
                                        .interrupt(
                                                RuntimeContext.builder()
                                                        .userId(tenant.userId())
                                                        .sessionId(cancelled.sessionId().toString())
                                                        .put(TenantContext.class, tenant)
                                                        .put(TenantContext.ATTR_KEY, tenant)
                                                        .put(
                                                                RunOrchestrationService.ATTR_RUN_ID,
                                                                cancelled.runId().toString())
                                                        .build());
                            }
                            return new CancelRunResponse(
                                    cancelled.runId().toString(),
                                    RunOrchestrationService.RUN_CANCELLED,
                                    cancelled.interrupted());
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/api/agents/{agentId}/runs/{runId}")
    public Mono<RunOrchestrationService.RunView> getRun(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String agentId,
            @PathVariable String runId) {
        return withRun(
                        tenantResolver.resolve(jwt != null ? jwt.getClaims() : Map.of()),
                        agentId,
                        runId)
                .map(
                        request ->
                                orchestration
                                        .getRun(
                                                request.tenant(),
                                                request.agentId(),
                                                request.runId())
                                        .orElseThrow(
                                                () ->
                                                        new ResponseStatusException(
                                                                HttpStatus.NOT_FOUND,
                                                                "Run not found")));
    }

    @GetMapping("/api/agents/{agentId}/runs/{runId}/tasks")
    public Mono<List<RunOrchestrationService.TaskView>> getRunTasks(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String agentId,
            @PathVariable String runId) {
        return withRun(
                        tenantResolver.resolve(jwt != null ? jwt.getClaims() : Map.of()),
                        agentId,
                        runId)
                .map(
                        request ->
                                orchestration.getTasks(
                                        request.tenant(), request.agentId(), request.runId()));
    }

    @GetMapping("/api/agents/{agentId}/runs/{runId}/attempts")
    public Mono<List<RunOrchestrationService.AttemptView>> getRunAttempts(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String agentId,
            @PathVariable String runId) {
        return withRun(
                        tenantResolver.resolve(jwt != null ? jwt.getClaims() : Map.of()),
                        agentId,
                        runId)
                .map(
                        request ->
                                orchestration.getAttempts(
                                        request.tenant(), request.agentId(), request.runId()));
    }

    @GetMapping("/api/agents/{agentId}/runs/{runId}/events")
    public Mono<List<RunOrchestrationService.RunEventView>> getRunEvents(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String agentId,
            @PathVariable String runId,
            @RequestParam(defaultValue = "0") long afterSeq,
            @RequestParam(defaultValue = "200") int limit) {
        return withRun(
                        tenantResolver.resolve(jwt != null ? jwt.getClaims() : Map.of()),
                        agentId,
                        runId)
                .map(
                        request ->
                                orchestration.getEvents(
                                        request.tenant(),
                                        request.agentId(),
                                        request.runId(),
                                        afterSeq,
                                        limit));
    }

    private Flux<ServerSentEvent<String>> degradationBlockedStream(
            ChatRequest request, DegradationManager.Decision decision, boolean persistable) {
        long startedNanos = System.nanoTime();
        String threadId =
                request != null && request.sessionId() != null && !request.sessionId().isBlank()
                        ? request.sessionId()
                        : UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        Map<String, Object> payload = new HashMap<>();
        payload.put("code", "RUNTIME_DEGRADED");
        payload.put("message", decision.message());
        payload.put("action", decision.action());
        payload.put("dependencies", decision.dependencies());
        AguiEvent errorEvent = new AguiEvent.Custom(threadId, runId, "error", payload);
        metrics.recordChatStream(
                "blocked", persistable, sandboxType, System.nanoTime() - startedNanos);
        return Flux.just(toSse(errorEvent));
    }

    private ServerSentEvent<String> toSse(AguiEvent event) {
        // encodeToJson returns " {json}"; trim the SSE-compatibility leading space — Spring writes
        // the "data:" prefix itself.
        return ServerSentEvent.<String>builder().data(encoder.encodeToJson(event).trim()).build();
    }

    /**
     * Builds the user-message metadata. When the request carries {@code confirmResults} (a HITL
     * resume), they are attached under {@link Msg#METADATA_CONFIRM_RESULTS} so the agent applies
     * the user's approve/deny decisions to the paused tool calls. Returns an empty map otherwise.
     */
    private static Map<String, Object> buildMetadata(ChatRequest request) {
        if (request.confirmResults() == null || request.confirmResults().isEmpty()) {
            return Map.of();
        }
        List<ConfirmResult> results =
                request.confirmResults().stream()
                        .map(
                                r -> {
                                    Map<String, Object> input =
                                            r.input() != null ? r.input() : Map.of();
                                    // content is the raw JSON of the tool arguments; the
                                    // framework's
                                    // tool-execution validation requires it to be non-null.
                                    String contentJson = JsonUtils.getJsonCodec().toJson(input);
                                    return new ConfirmResult(
                                            r.confirmed(),
                                            ToolUseBlock.builder()
                                                    .id(r.toolCallId())
                                                    .name(r.toolName())
                                                    .input(input)
                                                    .content(contentJson)
                                                    .build());
                                })
                        .toList();
        return Map.of(Msg.METADATA_CONFIRM_RESULTS, results);
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

    private static String errorMessage(Throwable error) {
        Throwable t = unwrapRuntimeWrapper(error);
        StringBuilder sb = new StringBuilder(messageOf(t));
        Throwable cause = t.getCause();
        int depth = 0;
        while (cause != null && depth < 4) {
            String msg = messageOf(cause);
            if (!msg.isBlank() && !sb.toString().contains(msg)) {
                sb.append("; caused by: ").append(msg);
            }
            cause = cause.getCause();
            depth++;
        }
        return sb.toString();
    }

    private static Throwable unwrapRuntimeWrapper(Throwable error) {
        if (error instanceof RuntimeException
                && error.getCause() != null
                && String.valueOf(error.getMessage())
                        .contains(error.getCause().getClass().getName())) {
            return error.getCause();
        }
        return error;
    }

    private static String messageOf(Throwable t) {
        String msg = t.getMessage();
        return msg != null && !msg.isBlank() ? msg : t.getClass().getSimpleName();
    }

    /** Captured agent + session ids for a resolved run. */
    private Mono<RunRequest> withRun(TenantContext tenant, String agentId, String runId) {
        if (!orchestrationEnabled || !isPersistable(tenant)) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        }
        return Mono.fromCallable(
                        () ->
                                new RunRequest(
                                        tenant,
                                        parseUuid(agentId, "agentId"),
                                        parseUuid(runId, "runId")))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, field + " not found");
        }
    }

    private record ResolvedRun(
            UUID agentId,
            UUID sessionId,
            UUID triggerMessageId,
            UUID runId,
            UUID rootAgentRunId,
            String status,
            boolean reused) {}

    private record RunRequest(TenantContext tenant, UUID agentId, UUID runId) {}

    /**
     * Captures the assistant's final content blocks so the reply can be persisted as structured
     * {@code content_json}. Prefers the terminal {@link AgentResultEvent}'s {@link Msg#getContent()}
     * (text + tool calls + reasoning); falls back to accumulating {@link TextBlockDeltaEvent} deltas
     * when no result event is emitted (e.g. an interrupted/erroring run).
     */
    private static final class AssistantContentAccumulator {
        private List<ContentBlock> resultBlocks = null;
        private final StringBuilder textBuffer = new StringBuilder();

        void onEvent(AgentEvent event) {
            if (event instanceof AgentResultEvent e && e.getResult() != null) {
                List<ContentBlock> blocks = e.getResult().getContent();
                if (blocks != null && !blocks.isEmpty()) {
                    resultBlocks = blocks;
                }
            } else if (event instanceof TextBlockDeltaEvent e
                    && e.getDelta() != null
                    && !e.getDelta().isEmpty()) {
                textBuffer.append(e.getDelta());
            }
        }

        List<ContentBlock> blocks() {
            if (resultBlocks != null) {
                return resultBlocks;
            }
            if (textBuffer.length() == 0) {
                return List.of();
            }
            return List.of(
                    io.agentscope.core.message.TextBlock.builder()
                            .text(textBuffer.toString())
                            .build());
        }
    }
}
