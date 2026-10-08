/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.model;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ContextWindowAwareModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.InputTokenAwareModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelContextProfile;
import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.PurposeBindableModel;
import io.agentscope.core.model.StepBindableModel;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.memory.compaction.ContextWindowExceededException;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.config.TenantRlsWebFilter;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.modelinvocation.ModelInvocationRepository;
import io.agentscope.saas.domain.modelinvocation.ModelInvocationRepository.Scope;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import io.agentscope.saas.sandbox.SandboxRuntimeAttributes;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** The deployed model boundary: trusted identity, fixed route, purpose limits and durable usage. */
@Service
@Primary
public class ModelInvocationService
        implements PurposeBindableModel, ContextWindowAwareModel, InputTokenAwareModel {
    private final ModelCatalog catalog;
    private final ModelInvocationRepository repository;
    private final ModelInvocationLedgerService ledger;
    private final SaasProperties properties;

    public ModelInvocationService(
            ModelCatalog catalog,
            ModelInvocationRepository repository,
            ModelInvocationLedgerService ledger,
            SaasProperties properties) {
        this.catalog = catalog;
        this.repository = repository;
        this.ledger = ledger;
        this.properties = properties;
    }

    @Override
    public boolean managesUsage() {
        return true;
    }

    @Override
    public String getModelName() {
        return catalog.getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return catalog.supportsNativeStructuredOutput();
    }

    @Override
    public long estimateInputTokens(List<Msg> messages, List<ToolSchema> tools) {
        return catalog.estimateInputTokens(messages, tools);
    }

    @Override
    public ModelContextProfile resolveContextProfile(List<Msg> messages) {
        return catalog.resolveContextProfile(messages);
    }

    @Override
    public ModelContextProfile resolveContextProfile(RuntimeContext context) {
        return ((ContextWindowAwareModel) bindToPurpose(context, Purpose.REASONING))
                .resolveContextProfile(context);
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.error(
                new IllegalStateException("A trusted model invocation binding is required"));
    }

    @Override
    public Model bindToStep(RuntimeContext context, List<Msg> messages) {
        Scope scope = scope(context);
        boolean routed = false;
        if (messages != null)
            for (Msg message : messages) {
                Object org =
                        message.getMetadata() != null
                                ? message.getMetadata().get(ModelCatalog.ORG_ID_KEY)
                                : null;
                if (org != null) {
                    if (!scope.orgId().toString().equals(org.toString()))
                        throw new IllegalArgumentException("Model route tenant mismatch");
                    routed = true;
                }
            }
        var policy = policy(scope.orgId(), Purpose.REASONING);
        return bound(
                context,
                scope,
                Purpose.REASONING,
                routed
                        ? withOrganization(scope, () -> catalog.bindToStep(context, messages))
                        : selectedRoute(context, scope),
                policy);
    }

    @Override
    public Model bindToContext(RuntimeContext context) {
        return bindToPurpose(context, Purpose.MEMORY_EXTRACT);
    }

    @Override
    public Model bindToPurpose(RuntimeContext context, Purpose purpose) {
        return helper(context, purpose, null);
    }

    private Model helper(RuntimeContext context, Purpose purpose, Model inherited) {
        Scope scope = scope(context);
        var policy = policy(scope.orgId(), purpose);
        Model route =
                policy.modelId() != null && !policy.modelId().isBlank()
                        ? withOrganization(
                                scope,
                                () -> catalog.bindToOrganization(scope.orgId(), policy.modelId()))
                        : inherited != null ? inherited : selectedRoute(context, scope);
        return bound(context, scope, purpose, route, policy);
    }

    private Model selectedRoute(RuntimeContext context, Scope scope) {
        return withOrganization(
                scope,
                () ->
                        catalog.bindToOrganization(
                                scope.orgId(), context.get(ContextWindowAwareModel.MODEL_ID_KEY)));
    }

    private static <T> T withOrganization(Scope scope, Supplier<T> action) {
        String previous = TenantContextHolder.getOrgId();
        TenantContextHolder.setOrgId(scope.orgId().toString());
        try {
            return action.get();
        } finally {
            TenantContextHolder.setOrgId(previous);
        }
    }

    private Bound bound(
            RuntimeContext context,
            Scope scope,
            Purpose purpose,
            Model route,
            ModelInvocationRepository.PurposePolicy policy) {
        var profile = ((ContextWindowAwareModel) route).resolveContextProfile(context);
        var limits =
                new InvocationLimits(
                        Math.min(policy.maxInputTokens(), profile.inputTokenBudget()),
                        Math.min(policy.maxOutputTokens(), profile.maxOutputTokens()),
                        Duration.ofSeconds(policy.timeoutSeconds()),
                        policy.version());
        return new Bound(
                scope, TenantContext.from(context).tokenQuota(), purpose, route, profile, limits);
    }

    public ModelInvocationRepository.PurposePolicy policy(UUID orgId, Purpose purpose) {
        int input = 8000, output = 1024, timeout = 30;
        switch (purpose) {
            case REASONING -> {
                input = Integer.MAX_VALUE;
                output = Integer.MAX_VALUE;
                timeout = properties.getModel().getInvocations().getReasoningTimeoutSeconds();
            }
            case COMPACTION -> {
                input = 16000;
                output = 2048;
                timeout = 60;
            }
            case MEMORY_CONSOLIDATE -> {
                input = 16000;
                output = 4000;
                timeout = 60;
            }
            case VERIFY -> {
                timeout = 60;
            }
            default -> {}
        }
        if (purpose != Purpose.REASONING) {
            var override = repository.policy(orgId, purpose.name());
            if (override.isPresent()) return override.get();
        }
        if (timeout < 1)
            throw new IllegalArgumentException("Model invocation timeout must be positive");
        return new ModelInvocationRepository.PurposePolicy(
                orgId, purpose.name(), null, input, output, timeout, "defaults-v1");
    }

    private final class Bound implements PurposeBindableModel, BoundInvocation {
        private final Scope scope;
        private final long monthlyQuota;
        private final Purpose purpose;
        private final Model delegate;
        private final ModelContextProfile profile;
        private final InvocationLimits limits;
        private final String version;

        Bound(
                Scope scope,
                long monthlyQuota,
                Purpose purpose,
                Model delegate,
                ModelContextProfile profile,
                InvocationLimits limits) {
            this.scope = scope;
            this.monthlyQuota = monthlyQuota;
            this.purpose = purpose;
            this.delegate = delegate;
            this.profile = profile;
            this.limits = limits;
            this.version = ((StepBindableModel.BoundModel) delegate).routeVersion();
        }

        @Override
        public boolean managesUsage() {
            return true;
        }

        @Override
        public String routeVersion() {
            return version;
        }

        @Override
        public String getModelName() {
            return delegate.getModelName();
        }

        @Override
        public boolean supportsNativeStructuredOutput() {
            return delegate.supportsNativeStructuredOutput();
        }

        @Override
        public InvocationLimits invocationLimits() {
            return limits;
        }

        @Override
        public ModelContextProfile resolveContextProfile(RuntimeContext ignored) {
            return profile;
        }

        @Override
        public ModelContextProfile resolveContextProfile(List<Msg> ignored) {
            return profile;
        }

        @Override
        public long estimateInputTokens(List<Msg> messages, List<ToolSchema> tools) {
            return TokenCounterUtil.calculateToken(messages, tools, delegate);
        }

        @Override
        public Model bindToStep(RuntimeContext ignored, List<Msg> messages) {
            return this;
        }

        @Override
        public Model bindToContext(RuntimeContext ctx) {
            return bindToPurpose(ctx, Purpose.MEMORY_EXTRACT);
        }

        @Override
        public Model bindToPurpose(RuntimeContext ctx, Purpose nextPurpose) {
            if (!scope(ctx).orgId().equals(scope.orgId())
                    || !scope(ctx).userId().equals(scope.userId()))
                throw new IllegalArgumentException("Bound model ownership mismatch");
            return helper(ctx, nextPurpose, delegate);
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(
                            () -> {
                                requireCurrentBinding();
                                rejectRouteOverrides(options);
                                long estimate = estimateInputTokens(messages, tools);
                                if (estimate < 0 || estimate > limits.inputTokens())
                                    return Flux.error(
                                            new ContextWindowExceededException(
                                                    "Input exceeds "
                                                            + purpose
                                                            + " budget for model "
                                                            + profile.modelId()));
                                int requested = limits.outputTokens();
                                if (options != null && options.getMaxTokens() != null)
                                    requested = Math.min(requested, options.getMaxTokens());
                                if (options != null && options.getMaxCompletionTokens() != null)
                                    requested =
                                            Math.min(requested, options.getMaxCompletionTokens());
                                if (requested < 1)
                                    return Flux.error(
                                            new IllegalArgumentException(
                                                    "Output tokens must be positive"));
                                final int output = requested;
                                var usage = new UsageAccumulator(estimate);
                                Mono<ModelInvocationLedgerService.Admission> admission =
                                        Mono.fromCallable(
                                                        () -> {
                                                            var result =
                                                                    ledger.admit(
                                                                            scope,
                                                                            purpose.name(),
                                                                            profile.modelId(),
                                                                            version
                                                                                    + ":"
                                                                                    + limits
                                                                                            .policyVersion(),
                                                                            estimate,
                                                                            output,
                                                                            OffsetDateTime.now(
                                                                                            ZoneOffset
                                                                                                    .UTC)
                                                                                    .plus(
                                                                                            limits
                                                                                                    .timeout()),
                                                                            monthlyQuota);
                                                            if (!result.permitted())
                                                                throw new InvocationRejectedException(
                                                                        result.rejection());
                                                            return result;
                                                        })
                                                .subscribeOn(Schedulers.boundedElastic());
                                return Flux.usingWhen(
                                        admission,
                                        admitted -> {
                                            requireCurrentBinding();
                                            Duration remaining =
                                                    Duration.between(
                                                            OffsetDateTime.now(ZoneOffset.UTC),
                                                            admitted.deadline());
                                            if (remaining.isNegative() || remaining.isZero())
                                                return Flux.error(
                                                        new InvocationRejectedException(
                                                                "MODEL_INVOCATION_SCOPE_EXPIRED"));
                                            GenerateOptions.Builder capBuilder =
                                                    GenerateOptions.builder();
                                            if (options != null
                                                    && options.getMaxCompletionTokens() != null)
                                                capBuilder.maxCompletionTokens(
                                                        admitted.outputTokens());
                                            else capBuilder.maxTokens(admitted.outputTokens());
                                            GenerateOptions caps = capBuilder.build();
                                            var requestOptions =
                                                    GenerateOptions.mergeOptions(caps, options);
                                            return delegate.stream(messages, tools, requestOptions)
                                                    .doOnNext(usage::add)
                                                    .takeUntilOther(
                                                            Mono.delay(remaining)
                                                                    .then(
                                                                            Mono.error(
                                                                                    new ModelException(
                                                                                            "Model"
                                                                                                + " invocation"
                                                                                                + " exceeded"
                                                                                                + " total"
                                                                                                + " deadline"))));
                                        },
                                        admitted ->
                                                settle(admitted, usage, "SUCCEEDED", null)
                                                        .flatMap(
                                                                active ->
                                                                        active
                                                                                ? Mono.empty()
                                                                                : Mono.error(
                                                                                        new InvocationRejectedException(
                                                                                                "MODEL_INVOCATION_SCOPE_EXPIRED"))),
                                        (admitted, error) ->
                                                settle(
                                                                admitted,
                                                                usage,
                                                                "FAILED",
                                                                error.getClass().getSimpleName())
                                                        .then(),
                                        admitted ->
                                                settle(
                                                                admitted,
                                                                usage,
                                                                "CANCELLED",
                                                                "CLIENT_CANCELLED")
                                                        .then());
                            })
                    .subscribeOn(Schedulers.boundedElastic())
                    .contextWrite(
                            ctx ->
                                    ctx.put(
                                            TenantRlsWebFilter.ORG_ID_KEY,
                                            scope.orgId().toString()));
        }

        private void requireCurrentBinding() {
            withOrganization(
                    scope,
                    () -> {
                        catalog.requireCurrentBinding(scope.orgId(), delegate);
                        return null;
                    });
            if (!policy(scope.orgId(), purpose).version().equals(limits.policyVersion()))
                throw new InvocationRejectedException("MODEL_INVOCATION_POLICY_CHANGED");
        }

        private Mono<Boolean> settle(
                ModelInvocationLedgerService.Admission admitted,
                UsageAccumulator usage,
                String status,
                String reason) {
            return Mono.fromCallable(
                            () -> {
                                var snapshot = usage.snapshot();
                                return ledger.settle(
                                        scope,
                                        admitted.id(),
                                        status,
                                        snapshot.input(),
                                        snapshot.output(),
                                        snapshot.total(),
                                        snapshot.reported(),
                                        reason);
                            })
                    .subscribeOn(Schedulers.boundedElastic());
        }
    }

    private static Scope scope(RuntimeContext context) {
        TenantContext tenant = TenantContext.from(context);
        if (tenant == null || tenant.orgId() == null || tenant.userId() == null)
            throw new IllegalArgumentException("Authenticated model invocation scope is required");
        if (context.getUserId() != null && !context.getUserId().equals(tenant.userId()))
            throw new IllegalArgumentException("Model invocation user mismatch");
        UUID org = UUID.fromString(tenant.orgId()), user = UUID.fromString(tenant.userId());
        UUID agentRun = uuid(context.get(RunOrchestrationService.ATTR_AGENT_RUN_ID));
        UUID run = uuid(context.get(RunOrchestrationService.ATTR_RUN_ID));
        UUID task = uuid(context.get(SandboxRuntimeAttributes.ATTR_TASK_ID));
        UUID attempt = uuid(context.get(SandboxRuntimeAttributes.ATTR_ATTEMPT_ID));
        String leaseOwner = context.get(SandboxRuntimeAttributes.ATTR_LEASE_OWNER);
        if (run == null && task == null && agentRun == null && attempt == null) {
            return new Scope(org, user, null, null, null, null, null);
        }
        if (run == null
                || task == null
                || (agentRun == null
                        && (attempt == null || leaseOwner == null || leaseOwner.isBlank())))
            throw new IllegalArgumentException("Incomplete model invocation Run scope");
        return new Scope(org, user, run, task, agentRun, attempt, leaseOwner);
    }

    private static UUID uuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private static void rejectRouteOverrides(GenerateOptions options) {
        if (options == null) return;
        if (options.getApiKey() != null
                || options.getBaseUrl() != null
                || options.getModelName() != null
                || options.getEndpointPath() != null)
            throw new IllegalArgumentException(
                    "Governed model routes cannot be overridden by generation options");
        if (!options.getAdditionalHeaders().isEmpty()
                || !options.getAdditionalQueryParams().isEmpty())
            throw new IllegalArgumentException(
                    "Governed model transport options cannot be overridden");
        if (options.getAdditionalBodyParams() != null)
            for (var key :
                    List.of("model", "messages", "tools", "max_tokens", "max_completion_tokens"))
                if (options.getAdditionalBodyParams().containsKey(key))
                    throw new IllegalArgumentException(
                            "Model generation budget override is forbidden");
    }

    private static final class UsageAccumulator {
        private final long estimate;
        private long input, output, total, outputCharacters;
        private boolean reported;

        UsageAccumulator(long estimate) {
            this.estimate = estimate;
        }

        synchronized void add(ChatResponse response) {
            ChatUsage usage = response.getUsage();
            if (usage != null && (usage.getInputTokens() > 0 || usage.getOutputTokens() > 0)) {
                reported = true;
                input = Math.max(input, usage.getInputTokens());
                output = Math.max(output, usage.getOutputTokens());
                total = Math.max(total, usage.getTotalTokens());
            }
            if (response.getContent() != null)
                for (var block : response.getContent())
                    outputCharacters =
                            Math.addExact(
                                    outputCharacters,
                                    io.agentscope.core.util.JsonUtils.getJsonCodec()
                                            .toJson(block)
                                            .length());
        }

        synchronized UsageSnapshot snapshot() {
            long in = input > 0 ? input : estimate;
            boolean outputReported = output > 0 || outputCharacters == 0;
            long out = outputReported ? output : (outputCharacters + 1) / 2;
            boolean fullyReported = reported && (input > 0 || estimate == 0) && outputReported;
            return new UsageSnapshot(in, out, Math.max(total, in + out), fullyReported);
        }
    }

    private record UsageSnapshot(long input, long output, long total, boolean reported) {}

    public static class InvocationRejectedException extends RuntimeException {
        public InvocationRejectedException(String code) {
            super(code);
        }
    }
}
