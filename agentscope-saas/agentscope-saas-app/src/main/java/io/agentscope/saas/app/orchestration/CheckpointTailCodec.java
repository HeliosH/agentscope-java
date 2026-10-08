/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.orchestration;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.StepSnapshot;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.memory.session.SessionArchiveStore;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.memory.PgSessionArchiveStore;
import io.agentscope.saas.app.memory.RuntimeBodyService;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.memory.RuntimeBodyRepository;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository.Scope;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Bounded working-view snapshots, not a substitution of raw source for transformed context. */
@Component
public class CheckpointTailCodec {
    public record Binding(RuntimeContext context, String agentLabel) {}

    public record Item(UUID bodyId, long contentBytes, String messageJson) {}

    public record Envelope(
            int version,
            Scope scope,
            UUID archiveStreamId,
            long archiveCursor,
            String tailHash,
            List<Item> items) {}

    public record Prepared(Envelope envelope, String json) {
        public List<UUID> bodyIds() {
            return envelope.items().stream()
                    .map(Item::bodyId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted()
                    .toList();
        }
    }

    private record Plan(Item item, String bodyJson) {}

    private record Candidate(int index, Plan plan, long savings) {}

    private final PgSessionArchiveStore archive;
    private final RuntimeBodyRepository repository;
    private final RuntimeBodyService bodies;
    private final SaasProperties properties;

    public CheckpointTailCodec(
            PgSessionArchiveStore archive,
            RuntimeBodyRepository repository,
            RuntimeBodyService bodies,
            SaasProperties properties) {
        this.archive = archive;
        this.repository = repository;
        this.bodies = bodies;
        this.properties = properties;
        var cfg = properties.getRuntimeArchive();
        if (cfg.getCheckpointInlineMaxBytes() < 256
                || cfg.getCheckpointInlineMaxBytes() > cfg.getMaxMessageBytes()
                || cfg.getCheckpointMaxJsonBytes() < 1024
                || cfg.getCheckpointMaxJsonBytes() > 33554432)
            throw new IllegalArgumentException("Invalid checkpoint storage policy");
    }

    public boolean enabled() {
        var cfg = properties.getRuntimeArchive();
        return cfg.isEnabled() && cfg.isLightweightCheckpointsEnabled();
    }

    public Scope authorize(UUID org, UUID run, Binding binding) {
        requireBinding(run, binding);
        Scope scope =
                archive.checkpointSnapshot(
                                binding.context(), binding.agentLabel(), key(binding), List.of())
                        .scope();
        if (!org.equals(scope.orgId()))
            throw new IllegalStateException("CHECKPOINT_SCOPE_MISMATCH");
        return scope;
    }

    public Prepared prepare(UUID org, UUID run, Binding binding, List<Msg> tail, long jsonBudget) {
        requireBinding(run, binding);
        if (tail.size() > 500) throw new IllegalArgumentException("CHECKPOINT_TAIL_LIMIT");
        var ids =
                tail.stream()
                        .filter(SessionArchiveStore::sourceMessage)
                        .map(Msg::getId)
                        .distinct()
                        .toList();
        var snapshot =
                archive.checkpointSnapshot(
                        binding.context(), binding.agentLabel(), key(binding), ids);
        if (!org.equals(snapshot.scope().orgId()))
            throw new IllegalStateException("CHECKPOINT_SCOPE_MISMATCH");
        return owned(
                snapshot.scope(),
                () -> {
                    var sources = new HashMap<String, RuntimeMessageRepository.Message>();
                    snapshot.messages()
                            .forEach(message -> sources.put(message.messageId(), message));
                    var plans = new ArrayList<Plan>();
                    var payloads = new ArrayList<String>();
                    long total = 0;
                    var cfg = properties.getRuntimeArchive();
                    for (Msg message : tail) {
                        String full = JsonUtils.getJsonCodec().toJson(message);
                        String payload = SessionArchiveStore.payload(message);
                        payloads.add(payload);
                        long bytes = bytes(payload);
                        total = Math.addExact(total, bytes(full));
                        if (bytes > cfg.getMaxMessageBytes() || total > cfg.getMaxWindowBytes())
                            throw new IllegalArgumentException("CHECKPOINT_TAIL_BYTE_LIMIT");
                        var source = sources.get(message.getId());
                        if (SessionArchiveStore.sourceMessage(message)) {
                            Object marker =
                                    message.getMetadata()
                                            .get(SessionArchiveStore.PROJECTION_SOURCE_HASH);
                            String hash =
                                    marker == null
                                            ? SessionArchiveStore.digest(message)
                                            : marker.toString();
                            if (source == null || !source.contentHash().equals(hash))
                                throw new IllegalStateException("CHECKPOINT_SOURCE_NOT_COMMITTED");
                        }
                        if (bytes(full) <= cfg.getCheckpointInlineMaxBytes()) {
                            plans.add(new Plan(new Item(null, bytes(full), full), null));
                            continue;
                        }
                        plans.add(bodyPlan(full, payload));
                    }
                    String hash = StepSnapshot.fingerprint(tail);
                    var envelope =
                            new Envelope(
                                    2,
                                    snapshot.scope(),
                                    snapshot.streamId(),
                                    snapshot.cursor(),
                                    hash,
                                    plans.stream().map(Plan::item).toList());
                    fitBudget(envelope, plans, payloads, jsonBudget);
                    // Resolve all reusable bodies before the first upload; UUID sizes are fixed.
                    for (int i = 0; i < plans.size(); i++)
                        plans.set(
                                i,
                                reuseBody(
                                        snapshot.scope(),
                                        sources.get(tail.get(i).getId()),
                                        plans.get(i)));
                    var items = new ArrayList<Item>();
                    for (var plan : plans) {
                        UUID id =
                                plan.bodyJson() == null
                                        ? plan.item().bodyId()
                                        : bodies.stage(snapshot.scope(), plan.bodyJson());
                        items.add(
                                new Item(
                                        id, plan.item().contentBytes(), plan.item().messageJson()));
                    }
                    var ready =
                            new Envelope(
                                    2,
                                    snapshot.scope(),
                                    snapshot.streamId(),
                                    snapshot.cursor(),
                                    hash,
                                    List.copyOf(items));
                    return new Prepared(ready, JsonUtils.getJsonCodec().toJson(ready));
                });
    }

    public List<Msg> restore(UUID org, UUID run, Binding binding, String json) {
        requireBinding(run, binding);
        long jsonBudget = properties.getRuntimeArchive().getCheckpointMaxJsonBytes();
        // JSONB adds formatting whitespace. Bound parsing, then measure the same schema as writes.
        if (bytes(json) > Math.multiplyExact(jsonBudget, 2))
            throw new IllegalArgumentException("CHECKPOINT_JSON_BYTE_LIMIT");
        Envelope envelope = JsonUtils.getJsonCodec().fromJson(json, Envelope.class);
        if (bytes(JsonUtils.getJsonCodec().toJson(envelope)) > jsonBudget)
            throw new IllegalArgumentException("CHECKPOINT_JSON_BYTE_LIMIT");
        var expected =
                archive.checkpointSnapshot(
                                binding.context(), binding.agentLabel(), key(binding), List.of())
                        .scope();
        if (envelope.version() != 2
                || !org.equals(expected.orgId())
                || !expected.equals(envelope.scope())
                || envelope.items() == null
                || envelope.items().size() > 500
                || envelope.archiveCursor() < 0)
            throw new IllegalStateException("CHECKPOINT_SCOPE_MISMATCH");
        return owned(
                expected,
                () -> {
                    var result = new ArrayList<Msg>();
                    long declaredBytes = 0;
                    long restoredBytes = 0;
                    for (Item item : envelope.items()) {
                        if (item.contentBytes() < 1)
                            throw new IllegalStateException("CHECKPOINT_INTEGRITY_FAILED");
                        declaredBytes = Math.addExact(declaredBytes, item.contentBytes());
                        if (declaredBytes > properties.getRuntimeArchive().getMaxWindowBytes())
                            throw new IllegalArgumentException("CHECKPOINT_TAIL_BYTE_LIMIT");
                        String full = item.messageJson();
                        if (item.bodyId() == null && bytes(full) != item.contentBytes())
                            throw new IllegalStateException("CHECKPOINT_INTEGRITY_FAILED");
                        if (item.bodyId() != null) {
                            Map<String, Object> header = map(full);
                            Map<String, Object> body =
                                    map(bodies.read(expected, item.bodyId(), item.contentBytes()));
                            if (header.containsKey("content")
                                    || !Objects.equals(header.get("id"), body.get("id"))
                                    || !Objects.equals(header.get("role"), body.get("role")))
                                throw new IllegalStateException("CHECKPOINT_INTEGRITY_FAILED");
                            header.put("content", body.get("content"));
                            full = JsonUtils.getJsonCodec().toJson(header);
                        }
                        restoredBytes = Math.addExact(restoredBytes, bytes(full));
                        if (restoredBytes > properties.getRuntimeArchive().getMaxWindowBytes())
                            throw new IllegalArgumentException("CHECKPOINT_TAIL_BYTE_LIMIT");
                        result.add(JsonUtils.getJsonCodec().fromJson(full, Msg.class));
                    }
                    if (!StepSnapshot.fingerprint(result).equals(envelope.tailHash()))
                        throw new IllegalStateException("CHECKPOINT_INTEGRITY_FAILED");
                    return List.copyOf(result);
                });
    }

    private static Plan bodyPlan(String full, String payload) {
        Map<String, Object> header = map(full);
        header.remove("content");
        return new Plan(
                new Item(new UUID(0, 0), bytes(payload), JsonUtils.getJsonCodec().toJson(header)),
                payload);
    }

    private static void fitBudget(
            Envelope envelope, List<Plan> plans, List<String> payloads, long budget) {
        long plannedBytes = bytes(JsonUtils.getJsonCodec().toJson(envelope));
        if (plannedBytes <= budget) return;
        var candidates = new ArrayList<Candidate>();
        for (int i = 0; i < plans.size(); i++) {
            Item inline = plans.get(i).item();
            if (inline.bodyId() != null) continue;
            Plan offloaded = bodyPlan(inline.messageJson(), payloads.get(i));
            long savings =
                    bytes(JsonUtils.getJsonCodec().toJson(inline))
                            - bytes(JsonUtils.getJsonCodec().toJson(offloaded.item()));
            if (savings > 0) candidates.add(new Candidate(i, offloaded, savings));
        }
        candidates.sort(
                Comparator.comparingLong(Candidate::savings)
                        .reversed()
                        .thenComparingInt(Candidate::index));
        // Item replacement preserves order and array overhead, including JSON string escaping.
        for (var candidate : candidates) {
            if (plannedBytes <= budget) break;
            plans.set(candidate.index(), candidate.plan());
            plannedBytes -= candidate.savings();
        }
        var fitted =
                new Envelope(
                        envelope.version(),
                        envelope.scope(),
                        envelope.archiveStreamId(),
                        envelope.archiveCursor(),
                        envelope.tailHash(),
                        plans.stream().map(Plan::item).toList());
        if (plannedBytes > budget || bytes(JsonUtils.getJsonCodec().toJson(fitted)) > budget)
            throw new IllegalArgumentException("CHECKPOINT_JSON_BYTE_LIMIT");
    }

    private Plan reuseBody(Scope scope, RuntimeMessageRepository.Message source, Plan plan) {
        if (plan.bodyJson() == null || source == null || source.bodyId() == null) return plan;
        var body = repository.findOwned(scope, source.bodyId()).orElse(null);
        if (body == null
                || !body.status().equals("READY")
                || body.sizeBytes() != plan.item().contentBytes()
                || !body.sha256()
                        .equals(
                                RuntimeBodyService.sha256(
                                        plan.bodyJson().getBytes(StandardCharsets.UTF_8))))
            return plan;
        return new Plan(
                new Item(body.id(), plan.item().contentBytes(), plan.item().messageJson()), null);
    }

    private static void requireBinding(UUID run, Binding binding) {
        if (binding == null
                || binding.context() == null
                || !run.toString()
                        .equals(binding.context().get(RunOrchestrationService.ATTR_RUN_ID)))
            throw new IllegalStateException("CHECKPOINT_SCOPE_MISMATCH");
    }

    private static String key(Binding binding) {
        return binding.context().getSessionId();
    }

    public static long bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(String json) {
        return new HashMap<>(JsonUtils.getJsonCodec().fromJson(json, Map.class));
    }

    private static <T> T owned(Scope scope, Supplier<T> operation) {
        String prior = TenantContextHolder.getOrgId();
        TenantContextHolder.setOrgId(scope.orgId().toString());
        try {
            return operation.get();
        } finally {
            TenantContextHolder.setOrgId(prior);
        }
    }
}
