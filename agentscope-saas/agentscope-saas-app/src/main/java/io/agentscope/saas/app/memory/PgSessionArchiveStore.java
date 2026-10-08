/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.app.memory;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.memory.session.SessionArchiveStore;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.orchestration.SessionRunFenceService;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository.Message;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository.Scope;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository.Stream;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository;
import io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import io.agentscope.saas.sandbox.SandboxRuntimeAttributes;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Tenant-scoped message commits; object IO is staged outside SQL transactions. */
@Service
public class PgSessionArchiveStore implements SessionArchiveStore {
    private final RuntimeMessageRepository repository;
    private final RunOrchestrationRepository runs;
    private final SaasProperties properties;
    private final TransactionTemplate transactions;
    private final RuntimeBodyService bodies;

    public PgSessionArchiveStore(
            RuntimeMessageRepository repository,
            RunOrchestrationRepository runs,
            SaasProperties properties,
            PlatformTransactionManager manager) {
        this(repository, runs, properties, manager, null);
    }

    @Autowired
    public PgSessionArchiveStore(
            RuntimeMessageRepository repository,
            RunOrchestrationRepository runs,
            SaasProperties properties,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            RuntimeBodyService bodies) {
        this.repository = repository;
        this.runs = runs;
        this.properties = properties;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setTimeout(10);
        this.bodies = bodies;
        var limits = properties.getRuntimeArchive();
        if (limits.getMaxBatchMessages() < 1
                || limits.getMaxBatchMessages() > 10000
                || limits.getMaxMessageBytes() < 1
                || limits.getMaxMessageBytes() > 33554432
                || limits.getMaxBatchBytes() < limits.getMaxMessageBytes()
                || limits.getMaxBatchBytes() > 268435456
                || limits.getMaxWindowBytes() < limits.getMaxMessageBytes()
                || limits.getMaxWindowBytes() > 67108864)
            throw new IllegalArgumentException("Invalid runtime archive limits");
    }

    private record Owner(UUID org, UUID user, UUID agent) {}

    private record Prepared(
            Msg message, String json, String hash, long bytes, boolean projection, UUID bodyId) {}

    private record Admission(Scope scope, Map<String, RuntimeMessageRepository.Identity> known) {}

    @Override
    public Receipt append(RuntimeContext context, String label, String key, List<Msg> messages) {
        var owner = owner(context);
        validateKey(label, 255);
        validateKey(key, 512);
        var limits = properties.getRuntimeArchive();
        if (messages.size() > limits.getMaxBatchMessages())
            throw new IllegalArgumentException("RUNTIME_ARCHIVE_BATCH_LIMIT");
        var prepared = new ArrayList<Prepared>();
        long bytes = 0;
        for (Msg message : messages) {
            if (!SessionArchiveStore.sourceMessage(message)) continue;
            validateKey(message.getId(), 255);
            Object marker = message.getMetadata().get(PROJECTION_SOURCE_HASH);
            String json = marker == null ? SessionArchiveStore.payload(message) : null;
            String hash = marker == null ? SessionArchiveStore.digest(message) : marker.toString();
            if (!hash.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid source hash");
            long size = json == null ? 0 : json.getBytes(StandardCharsets.UTF_8).length;
            bytes = Math.addExact(bytes, size);
            if (size > limits.getMaxMessageBytes() || bytes > limits.getMaxBatchBytes())
                throw new IllegalArgumentException("RUNTIME_ARCHIVE_BYTE_LIMIT");
            prepared.add(new Prepared(message, json, hash, size, marker != null, null));
        }
        var ids = prepared.stream().map(item -> item.message().getId()).distinct().toList();
        Admission admission =
                withOwner(
                        owner,
                        () ->
                                transactions.execute(
                                        tx -> {
                                            Scope scope = writeScope(context, owner, label, key);
                                            if (!repository.lockSession(scope))
                                                throw new IllegalStateException(
                                                        "Runtime session is not owned by caller");
                                            requireCurrentRun(context, scope);
                                            var known =
                                                    repository
                                                            .findStream(scope)
                                                            .map(
                                                                    stream ->
                                                                            identities(
                                                                                    scope,
                                                                                    stream.id(),
                                                                                    ids))
                                                            .orElseGet(HashMap::new);
                                            for (var item : prepared)
                                                checkIdentity(
                                                        known.get(item.message().getId()),
                                                        item.hash());
                                            return new Admission(scope, known);
                                        }));
        var staged = new ArrayList<Prepared>();
        var stagedById = new HashMap<String, Prepared>();
        withOwner(
                owner,
                () -> {
                    for (var item : prepared) {
                        Prepared result = item;
                        if (bodies != null
                                && !item.projection()
                                && !admission.known().containsKey(item.message().getId())
                                && bodies.offload(item.bytes())) {
                            Prepared prior = stagedById.get(item.message().getId());
                            if (prior != null) {
                                if (!prior.hash().equals(item.hash()))
                                    throw new IllegalStateException(
                                            "RUNTIME_MESSAGE_ID_CONTENT_CONFLICT");
                                result = prior;
                            } else {
                                UUID bodyId = bodies.stage(admission.scope(), item.json());
                                result =
                                        new Prepared(
                                                item.message(),
                                                preview(item.message(), item.bytes()),
                                                item.hash(),
                                                item.bytes(),
                                                false,
                                                bodyId);
                                stagedById.put(item.message().getId(), result);
                            }
                        }
                        staged.add(result);
                    }
                    return null;
                });
        return withOwner(
                owner,
                () ->
                        transactions.execute(
                                tx -> {
                                    Scope scope = writeScope(context, owner, label, key);
                                    if (!scope.equals(admission.scope()))
                                        throw new IllegalStateException(
                                                "RUNTIME_ARCHIVE_ADMISSION_CHANGED");
                                    if (!repository.lockSession(scope))
                                        throw new IllegalStateException(
                                                "Runtime session is not owned by caller");
                                    requireCurrentRun(context, scope);
                                    repository.ensureStream(UUID.randomUUID(), scope);
                                    Stream stream = repository.lockStream(scope);
                                    var existingIds = identities(scope, stream.id(), ids);
                                    long seq = stream.lastSeq();
                                    String parent = stream.lastMessageId();
                                    String lastInserted = stream.lastMessageId();
                                    int appended = 0;
                                    long insertedBytes = 0;
                                    UUID sourceRun =
                                            uuid(context.get(RunOrchestrationService.ATTR_RUN_ID));
                                    UUID sourceAgentRun =
                                            uuid(
                                                    context.get(
                                                            RunOrchestrationService
                                                                    .ATTR_AGENT_RUN_ID));
                                    for (var item : staged) {
                                        var existing = existingIds.get(item.message().getId());
                                        if (existing != null) {
                                            checkIdentity(existing, item.hash());
                                            parent = existing.messageId();
                                            continue;
                                        }
                                        if (admission.known().containsKey(item.message().getId()))
                                            throw new IllegalStateException(
                                                    "RUNTIME_ARCHIVE_ADMISSION_CHANGED");
                                        Message origin =
                                                item.projection()
                                                        ? repository
                                                                .findOwnedSource(
                                                                        scope,
                                                                        item.message().getId(),
                                                                        item.hash())
                                                                .orElseThrow(
                                                                        () ->
                                                                                new IllegalStateException(
                                                                                        "RUNTIME_PROJECTION_SOURCE_MISSING"))
                                                        : null;
                                        long contentBytes =
                                                origin != null
                                                        ? origin.contentBytes()
                                                        : item.bytes();
                                        insertedBytes = Math.addExact(insertedBytes, contentBytes);
                                        if (contentBytes > limits.getMaxMessageBytes()
                                                || insertedBytes > limits.getMaxBatchBytes())
                                            throw new IllegalArgumentException(
                                                    "RUNTIME_ARCHIVE_BYTE_LIMIT");
                                        seq = Math.addExact(seq, 1);
                                        UUID bodyId =
                                                origin != null ? origin.bodyId() : item.bodyId();
                                        if (bodyId != null) {
                                            if (bodies == null)
                                                throw new IllegalStateException(
                                                        "RUNTIME_BODY_BACKEND_UNAVAILABLE");
                                            bodies.attach(scope, bodyId);
                                        }
                                        repository.insert(
                                                scope,
                                                new Message(
                                                        stream.id(),
                                                        seq,
                                                        item.message().getId(),
                                                        parent,
                                                        origin != null
                                                                ? origin.sourceRunId()
                                                                : sourceRun,
                                                        origin != null
                                                                ? origin.sourceAgentRunId()
                                                                : sourceAgentRun,
                                                        origin != null
                                                                ? origin.role()
                                                                : item.message().getRole().name(),
                                                        origin != null
                                                                ? origin.payloadJson()
                                                                : item.json(),
                                                        item.hash(),
                                                        contentBytes,
                                                        OffsetDateTime.now(ZoneOffset.UTC),
                                                        bodyId));
                                        existingIds.put(
                                                item.message().getId(),
                                                new RuntimeMessageRepository.Identity(
                                                        seq, item.message().getId(), item.hash()));
                                        parent = item.message().getId();
                                        lastInserted = parent;
                                        appended++;
                                    }
                                    if (appended > 0)
                                        repository.advance(scope, stream.id(), seq, lastInserted);
                                    return new Receipt(seq, appended, reference(scope));
                                }));
    }

    @Override
    public List<Entry> readWindow(
            RuntimeContext context, String label, String key, Long after, Long before, int limit) {
        var owner = owner(context);
        validateWindow(after, before, limit);
        return withOwner(
                owner,
                () ->
                        repository
                                .findStream(owner.org(), owner.user(), owner.agent(), label, key)
                                .map(
                                        stream ->
                                                entries(
                                                        stream.scope(),
                                                        repository.window(
                                                                stream.scope(),
                                                                stream.id(),
                                                                after,
                                                                before,
                                                                limit,
                                                                properties
                                                                        .getRuntimeArchive()
                                                                        .getMaxWindowBytes()),
                                                        true))
                                .orElseGet(List::of));
    }

    public record Page(List<Entry> items, Long nextAfterSeq, Long nextBeforeSeq, boolean hasMore) {}

    public record Snapshot(Scope scope, UUID streamId, long cursor, List<Message> messages) {}

    /** Captures committed metadata only; no object GET under the session lock. */
    public Snapshot checkpointSnapshot(
            RuntimeContext context, String label, String key, List<String> ids) {
        var owner = owner(context);
        validateKey(label, 255);
        validateKey(key, 512);
        if (ids.size() > 500) throw new IllegalArgumentException("Checkpoint window too large");
        return withOwner(
                owner,
                () ->
                        transactions.execute(
                                tx -> {
                                    Scope scope = writeScope(context, owner, label, key);
                                    if (!repository.lockSession(scope))
                                        throw new IllegalStateException(
                                                "Runtime session is not owned by caller");
                                    requireCurrentRun(context, scope);
                                    Stream stream = repository.findStream(scope).orElse(null);
                                    return new Snapshot(
                                            scope,
                                            stream == null ? null : stream.id(),
                                            stream == null ? 0 : stream.lastSeq(),
                                            stream == null
                                                    ? List.of()
                                                    : repository.findMessageMetadata(
                                                            stream.id(), scope, ids));
                                }));
    }

    public Page page(
            TenantContext tenant,
            UUID agent,
            UUID session,
            String label,
            String key,
            Long after,
            Long before,
            int limit) {
        return page(tenant, agent, session, label, key, after, before, limit, true);
    }

    public Page page(
            TenantContext tenant,
            UUID agent,
            UUID session,
            String label,
            String key,
            Long after,
            Long before,
            int limit,
            boolean includeContent) {
        validateWindow(after, before, limit);
        var owner =
                new Owner(UUID.fromString(tenant.orgId()), UUID.fromString(tenant.userId()), agent);
        return withOwner(
                owner,
                () -> {
                    var stream =
                            repository
                                    .findStream(
                                            new Scope(
                                                    owner.org(),
                                                    owner.user(),
                                                    agent,
                                                    session,
                                                    label,
                                                    key))
                                    .orElse(null);
                    if (stream == null) return new Page(List.of(), after, before, false);
                    var messages =
                            repository.window(
                                    stream.scope(),
                                    stream.id(),
                                    after,
                                    before,
                                    limit,
                                    properties.getRuntimeArchive().getMaxWindowBytes());
                    Long nextAfter =
                            messages.isEmpty() ? after : messages.get(messages.size() - 1).seq();
                    Long nextBefore = messages.isEmpty() ? before : messages.get(0).seq();
                    boolean more =
                            repository.hasBeyond(
                                    stream.scope(),
                                    stream.id(),
                                    after != null ? nextAfter : null,
                                    after != null ? null : nextBefore);
                    if (messages.isEmpty() && more)
                        throw new IllegalStateException("RUNTIME_ARCHIVE_WINDOW_BYTES_TOO_SMALL");
                    return new Page(
                            entries(stream.scope(), messages, includeContent),
                            nextAfter,
                            nextBefore,
                            more);
                });
    }

    @Override
    public List<Hit> search(RuntimeContext context, String label, String query, int limit) {
        var owner = owner(context);
        if (query == null || query.isBlank() || query.length() > 500)
            throw new IllegalArgumentException("Invalid archive query");
        String pattern =
                "%"
                        + query.toLowerCase(Locale.ROOT)
                                .replace("!", "!!")
                                .replace("%", "!%")
                                .replace("_", "!_")
                        + "%";
        return withOwner(
                owner,
                () ->
                        repository
                                .search(
                                        owner.org(),
                                        owner.user(),
                                        owner.agent(),
                                        label,
                                        pattern,
                                        limit)
                                .stream()
                                .map(
                                        hit ->
                                                new Hit(
                                                        hit.agentLabel(),
                                                        hit.sessionKey(),
                                                        hit.seq(),
                                                        hit.role(),
                                                        hit.preview()))
                                .toList());
    }

    @Override
    public List<Session> list(RuntimeContext context, String label, int limit) {
        var owner = owner(context);
        return withOwner(
                owner,
                () ->
                        repository
                                .list(owner.org(), owner.user(), owner.agent(), label, limit)
                                .stream()
                                .map(
                                        stream ->
                                                new Session(
                                                        stream.scope().agentLabel(),
                                                        stream.scope().sessionKey(),
                                                        stream.lastSeq()))
                                .toList());
    }

    @Override
    public String reference(RuntimeContext context, String label, String key) {
        var owner = owner(context);
        return withOwner(
                owner,
                () ->
                        transactions.execute(
                                tx -> reference(writeScope(context, owner, label, key))));
    }

    private List<Entry> entries(Scope scope, List<Message> messages, boolean includeContent) {
        return messages.stream()
                .map(
                        message ->
                                new Entry(
                                        message.seq(),
                                        JsonUtils.getJsonCodec()
                                                .fromJson(
                                                        payload(scope, message, includeContent),
                                                        Msg.class)))
                .toList();
    }

    private String payload(Scope scope, Message message, boolean includeContent) {
        if (message.bodyId() == null || !includeContent) return message.payloadJson();
        if (bodies == null) throw new IllegalStateException("RUNTIME_BODY_BACKEND_UNAVAILABLE");
        String json = bodies.read(scope, message.bodyId(), message.contentBytes());
        Msg restored = JsonUtils.getJsonCodec().fromJson(json, Msg.class);
        if (!Objects.equals(restored.getId(), message.messageId())
                || !restored.getRole().name().equals(message.role())
                || !SessionArchiveStore.digest(restored).equals(message.contentHash()))
            throw new IllegalStateException("RUNTIME_BODY_INTEGRITY_FAILED");
        return json;
    }

    private Map<String, RuntimeMessageRepository.Identity> identities(
            Scope scope, UUID streamId, List<String> ids) {
        var map = new HashMap<String, RuntimeMessageRepository.Identity>();
        repository
                .findIdentities(streamId, scope, ids)
                .forEach(identity -> map.put(identity.messageId(), identity));
        return map;
    }

    private static void checkIdentity(RuntimeMessageRepository.Identity existing, String hash) {
        if (existing != null && !existing.contentHash().equals(hash))
            throw new IllegalStateException("RUNTIME_MESSAGE_ID_CONTENT_CONFLICT");
    }

    private static String preview(Msg message, long bytes) {
        String text = message.getTextContent();
        if (text == null || text.isBlank())
            text = JsonUtils.getJsonCodec().toJson(message.getContent());
        if (text.length() > 2048) {
            int end = Character.isHighSurrogate(text.charAt(2047)) ? 2047 : 2048;
            text = text.substring(0, end);
        }
        String name = message.getName();
        if (name != null && name.length() > 255) name = name.substring(0, 255);
        Msg preview =
                Msg.builder()
                        .id(message.getId())
                        .name(name)
                        .role(message.getRole())
                        .timestamp(message.getTimestamp())
                        .textContent(
                                "[Archived body preview; full content requires"
                                        + " includeContent=true]\n"
                                        + text)
                        .metadata(
                                Map.of("archiveBodyOffloaded", true, "archiveContentBytes", bytes))
                        .build();
        return JsonUtils.getJsonCodec().toJson(preview);
    }

    private Scope writeScope(RuntimeContext context, Owner owner, String label, String key) {
        UUID runId = uuid(context.get(RunOrchestrationService.ATTR_RUN_ID));
        var binding = context.get(SessionRunFenceService.Binding.class);
        UUID session =
                runId == null
                        ? (binding == null
                                ? uuid(context.getSessionId())
                                : binding.fence().sessionId())
                        : runs.findOwnedRun(runId, owner.org(), owner.user(), owner.agent())
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "Runtime run is not owned by caller"))
                                .sessionId();
        if (session == null)
            throw new IllegalArgumentException("Runtime archive needs a persistent session");
        return new Scope(owner.org(), owner.user(), owner.agent(), session, label, key);
    }

    private void requireCurrentRun(RuntimeContext context, Scope scope) {
        UUID run = uuid(context.get(RunOrchestrationService.ATTR_RUN_ID));
        if (run != null) {
            var fence =
                    runs.findCurrentSessionFence(
                                    run, scope.orgId(), scope.userId(), scope.agentId())
                            .orElseThrow(SessionExecutionRevokedException::new);
            if (!scope.sessionId().equals(fence.sessionId()))
                throw new SessionExecutionRevokedException();
        } else {
            var binding = context.get(SessionRunFenceService.Binding.class);
            if (binding != null
                    && (!binding.orgId().equals(scope.orgId())
                            || !binding.userId().equals(scope.userId())
                            || !binding.agentId().equals(scope.agentId())
                            || !runs.findSessionFence(
                                            scope.sessionId(),
                                            scope.orgId(),
                                            scope.userId(),
                                            scope.agentId())
                                    .filter(binding.fence()::equals)
                                    .isPresent())) throw new SessionExecutionRevokedException();
        }
    }

    private static Owner owner(RuntimeContext context) {
        var tenant = TenantContext.from(context);
        if (tenant == null
                || context.getUserId() == null
                || !context.getUserId().equals(tenant.userId()))
            throw new IllegalArgumentException("Authenticated runtime archive scope is required");
        UUID agent = uuid(context.get(SandboxRuntimeAttributes.ATTR_AGENT_ID));
        if (agent == null)
            throw new IllegalArgumentException("Runtime archive agent binding is required");
        return new Owner(UUID.fromString(tenant.orgId()), UUID.fromString(tenant.userId()), agent);
    }

    private static String reference(Scope scope) {
        return "runtime-archive:/api/agents/"
                + scope.agentId()
                + "/sessions/"
                + scope.sessionId()
                + "/runtime-messages?agentLabel="
                + URLEncoder.encode(scope.agentLabel(), StandardCharsets.UTF_8)
                + "&sessionKey="
                + URLEncoder.encode(scope.sessionKey(), StandardCharsets.UTF_8);
    }

    private static void validateWindow(Long after, Long before, int limit) {
        if (limit < 1
                || limit > 500
                || (after != null && after < 0)
                || (before != null && before < 1)
                || (after != null && before != null))
            throw new IllegalArgumentException("Invalid runtime archive window");
    }

    private static void validateKey(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max)
            throw new IllegalArgumentException("Invalid runtime archive key");
    }

    private static UUID uuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private static <T> T withOwner(Owner owner, Supplier<T> operation) {
        String prior = TenantContextHolder.getOrgId();
        TenantContextHolder.setOrgId(owner.org().toString());
        try {
            return operation.get();
        } finally {
            TenantContextHolder.setOrgId(prior);
        }
    }
}
