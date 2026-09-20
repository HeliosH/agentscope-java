/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.model;

import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.InputTokenAwareModel;
import io.agentscope.core.model.InputTokenEstimator;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.memory.compaction.ContextWindowExceededException;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import java.net.URI;
import java.util.List;
import reactor.core.publisher.Flux;

/** Provider estimates attached to actual deployment routes, including all failover candidates. */
final class EstimatedInputModel implements InputTokenAwareModel {
    private final Model delegate;
    private final InputTokenEstimator estimator;
    private final String officialModelName;

    private EstimatedInputModel(
            Model delegate, InputTokenEstimator estimator, String officialModelName) {
        this.delegate = delegate;
        this.estimator = estimator;
        this.officialModelName = officialModelName;
    }

    static Model openAi(Model delegate, String baseUrl, String modelName) {
        // Explicit allowlist: compatible gateways and similarly named models may tokenize
        // differently.
        if (!officialEndpoint(baseUrl)
                || !("gpt-4.1-mini".equals(modelName)
                        || "gpt-4.1-mini-2025-04-14".equals(modelName))) {
            return delegate;
        }
        // https://developers.openai.com/api/docs/guides/images-vision (2026-09-09):
        // 6144 patch budget * 1.62 multiplier, rounded up, plus one token rounding allowance.
        // Conservative image admission bound; text remains an estimate, not billing tokenization.
        int imageBound = 9955;
        return new EstimatedInputModel(
                delegate,
                (messages, tools) ->
                        TokenCounterUtil.calculateToken(
                                messages,
                                tools,
                                block -> {
                                    if (block instanceof ImageBlock) return imageBound;
                                    throw new ContextWindowExceededException(
                                            "No media token estimator for "
                                                    + block.getClass().getSimpleName()
                                                    + " on "
                                                    + modelName);
                                }),
                modelName);
    }

    static Model failover(Model delegate, List<Model> candidates) {
        List<Model> frozen = List.copyOf(candidates);
        return new EstimatedInputModel(
                delegate,
                (messages, tools) -> {
                    long maximum = 0;
                    for (Model candidate : frozen) {
                        maximum =
                                Math.max(
                                        maximum,
                                        TokenCounterUtil.calculateToken(
                                                messages, tools, candidate));
                    }
                    return maximum;
                },
                null);
    }

    private static boolean officialEndpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) return true; // OpenAIChatModel default endpoint.
        try {
            URI uri = URI.create(baseUrl);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "api.openai.com".equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && ("".equals(uri.getPath())
                            || "/".equals(uri.getPath())
                            || "/v1".equals(uri.getPath())
                            || "/v1/".equals(uri.getPath()));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    @Override
    public long estimateInputTokens(List<Msg> messages, List<ToolSchema> tools) {
        return estimator.estimate(messages, tools);
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.defer(
                () -> {
                    if (officialModelName != null
                            && options != null
                            && ((options.getModelName() != null
                                            && !officialModelName.equals(options.getModelName()))
                                    || (options.getBaseUrl() != null
                                            && !officialEndpoint(options.getBaseUrl())))) {
                        // An endpoint/model override invalidates the media estimate used by
                        // admission.
                        // Text-only requests retain the existing override compatibility.
                        TokenCounterUtil.calculateToken(messages, tools);
                    }
                    return delegate.stream(messages, tools, options);
                });
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return delegate.supportsNativeStructuredOutput();
    }
}
