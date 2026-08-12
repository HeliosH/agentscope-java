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
package io.agentscope.core.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.exception.OpenAIException;
import io.agentscope.core.model.transport.HttpTransportException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Deployment-level model traffic governance and failover.
 *
 * <p>Each delegate has an independent concurrency limit, QPM window, 429 cooldown, and circuit
 * breaker. A request fails over only when the selected model fails before emitting its first stream
 * item; switching after partial output would duplicate or corrupt the response.
 */
public final class ResilientModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(ResilientModel.class);

    private final List<Model> models;
    private final List<TrafficController> controllers;

    public ResilientModel(List<Model> models, Policy policy) {
        if (models == null || models.isEmpty()) {
            throw new IllegalArgumentException("at least one model is required");
        }
        this.models = List.copyOf(models);
        Objects.requireNonNull(policy, "policy must not be null");
        List<TrafficController> configured = new ArrayList<>(models.size());
        for (Model model : models) {
            configured.add(new TrafficController(model.getModelName(), policy));
        }
        this.controllers = List.copyOf(configured);
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return invoke(0, messages, tools, options);
    }

    private Flux<ChatResponse> invoke(
            int index, List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        Model selected = models.get(index);
        AtomicBoolean emitted = new AtomicBoolean(false);
        return controllers
                .get(index)
                .execute(() -> selected.stream(messages, tools, options))
                .doOnNext(ignored -> emitted.set(true))
                .onErrorResume(
                        error -> {
                            if (emitted.get()
                                    || index + 1 >= models.size()
                                    || !isRetryable(error)) {
                                return Flux.error(error);
                            }
                            Model fallback = models.get(index + 1);
                            log.warn(
                                    "Model {} unavailable before first output; failing over to {}:"
                                            + " {}",
                                    selected.getModelName(),
                                    fallback.getModelName(),
                                    error.toString());
                            return invoke(index + 1, messages, tools, options);
                        });
    }

    @Override
    public String getModelName() {
        return models.size() == 1
                ? models.get(0).getModelName()
                : models.get(0).getModelName() + "+failover";
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return models.stream().allMatch(Model::supportsNativeStructuredOutput);
    }

    private static boolean isRetryable(Throwable error) {
        return error instanceof ModelCapacityException
                || ExecutionConfig.RETRYABLE_ERRORS.test(error);
    }

    private static boolean isRateLimit(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof OpenAIException openAI
                    && Integer.valueOf(429).equals(openAI.getStatusCode())) {
                return true;
            }
            if (current instanceof HttpTransportException transport
                    && Integer.valueOf(429).equals(transport.getStatusCode())) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }

    /** Immutable traffic and failover policy shared by every configured model. */
    public record Policy(
            int maxConcurrent,
            int maxQueriesPerMinute,
            Duration acquireTimeout,
            Duration rateLimitCooldown,
            int circuitFailureThreshold,
            Duration circuitOpenDuration) {

        public Policy {
            if (maxConcurrent < 1) {
                throw new IllegalArgumentException("maxConcurrent must be positive");
            }
            if (maxQueriesPerMinute < 0) {
                throw new IllegalArgumentException("maxQueriesPerMinute must not be negative");
            }
            Objects.requireNonNull(acquireTimeout, "acquireTimeout must not be null");
            Objects.requireNonNull(rateLimitCooldown, "rateLimitCooldown must not be null");
            Objects.requireNonNull(circuitOpenDuration, "circuitOpenDuration must not be null");
            if (acquireTimeout.isNegative() || acquireTimeout.isZero()) {
                throw new IllegalArgumentException("acquireTimeout must be positive");
            }
            if (rateLimitCooldown.isNegative()) {
                throw new IllegalArgumentException("rateLimitCooldown must not be negative");
            }
            if (circuitFailureThreshold < 1) {
                throw new IllegalArgumentException("circuitFailureThreshold must be positive");
            }
            if (circuitOpenDuration.isNegative() || circuitOpenDuration.isZero()) {
                throw new IllegalArgumentException("circuitOpenDuration must be positive");
            }
        }
    }

    /** Retryable local capacity error so a configured fallback may serve the request. */
    public static final class ModelCapacityException extends RuntimeException {
        public ModelCapacityException(String message) {
            super(message);
        }

        public ModelCapacityException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @FunctionalInterface
    private interface StreamSupplier {
        Flux<ChatResponse> get();
    }

    private static final class TrafficController {
        private static final long QPM_WINDOW_NANOS = Duration.ofMinutes(1).toNanos();

        private final String modelName;
        private final Policy policy;
        private final Semaphore concurrency;
        private final Object qpmLock = new Object();
        private final Deque<Long> requestTimes = new ArrayDeque<>();
        private final AtomicLong cooldownUntilNanos = new AtomicLong();
        private final AtomicLong circuitOpenUntilNanos = new AtomicLong();
        private final AtomicInteger consecutiveFailures = new AtomicInteger();

        private TrafficController(String modelName, Policy policy) {
            this.modelName = modelName;
            this.policy = policy;
            this.concurrency = new Semaphore(policy.maxConcurrent(), true);
        }

        private Flux<ChatResponse> execute(StreamSupplier supplier) {
            return Flux.usingWhen(
                    acquire(),
                    ignored ->
                            Flux.defer(supplier::get)
                                    .doOnComplete(this::onSuccess)
                                    .doOnError(this::onFailure),
                    ignored -> release(),
                    (ignored, error) -> release(),
                    ignored -> release());
        }

        private Mono<Permit> acquire() {
            long deadline = System.nanoTime() + policy.acquireTimeout().toNanos();
            return checkCircuit()
                    .then(awaitDispatchWindow(deadline))
                    .then(acquireConcurrency(deadline));
        }

        private Mono<Void> checkCircuit() {
            long remaining = circuitOpenUntilNanos.get() - System.nanoTime();
            if (remaining <= 0) {
                return Mono.empty();
            }
            return Mono.error(
                    new ModelCapacityException(
                            "Model circuit is open for "
                                    + modelName
                                    + " (remaining "
                                    + Duration.ofNanos(remaining).toMillis()
                                    + " ms)"));
        }

        private Mono<Permit> acquireConcurrency(long deadline) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return Mono.error(capacityTimeout());
            }
            return Mono.fromCallable(() -> concurrency.tryAcquire(remaining, TimeUnit.NANOSECONDS))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(
                            acquired ->
                                    acquired
                                            ? Mono.just(Permit.INSTANCE)
                                            : Mono.error(capacityTimeout()))
                    .onErrorMap(
                            InterruptedException.class,
                            error -> {
                                Thread.currentThread().interrupt();
                                return new ModelCapacityException(
                                        "Interrupted while waiting for model " + modelName, error);
                            });
        }

        private Mono<Void> awaitDispatchWindow(long deadline) {
            return Mono.defer(
                    () -> {
                        long now = System.nanoTime();
                        long waitNanos = Math.max(0, cooldownUntilNanos.get() - now);
                        synchronized (qpmLock) {
                            prune(now);
                            if (waitNanos == 0 && hasQpmCapacity()) {
                                requestTimes.addLast(now);
                                return Mono.empty();
                            }
                            if (waitNanos == 0) {
                                waitNanos = requestTimes.getFirst() + QPM_WINDOW_NANOS - now;
                            }
                        }
                        if (now + waitNanos > deadline) {
                            return Mono.error(capacityTimeout());
                        }
                        return Mono.delay(Duration.ofNanos(Math.max(1, waitNanos)))
                                .then(awaitDispatchWindow(deadline));
                    });
        }

        private boolean hasQpmCapacity() {
            return policy.maxQueriesPerMinute() == 0
                    || requestTimes.size() < policy.maxQueriesPerMinute();
        }

        private void prune(long now) {
            while (!requestTimes.isEmpty() && requestTimes.getFirst() <= now - QPM_WINDOW_NANOS) {
                requestTimes.removeFirst();
            }
        }

        private ModelCapacityException capacityTimeout() {
            return new ModelCapacityException("Timed out waiting for model capacity: " + modelName);
        }

        private void onSuccess() {
            consecutiveFailures.set(0);
            circuitOpenUntilNanos.set(0);
        }

        private void onFailure(Throwable error) {
            if (!isRetryable(error)) {
                return;
            }
            long now = System.nanoTime();
            if (isRateLimit(error)) {
                cooldownUntilNanos.accumulateAndGet(
                        now + policy.rateLimitCooldown().toNanos(), Math::max);
            }
            if (consecutiveFailures.incrementAndGet() >= policy.circuitFailureThreshold()) {
                circuitOpenUntilNanos.set(now + policy.circuitOpenDuration().toNanos());
                consecutiveFailures.set(0);
                log.warn(
                        "Opened model circuit for {} during {} after repeated transient failures",
                        modelName,
                        policy.circuitOpenDuration());
            }
        }

        private Mono<Void> release() {
            return Mono.fromRunnable(concurrency::release);
        }
    }

    private enum Permit {
        INSTANCE
    }
}
