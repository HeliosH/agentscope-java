package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ExecutionConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ToolRetrySafetyTest {
    private static ExecutionConfig config(boolean force) {
        var builder =
                ExecutionConfig.builder()
                        .maxAttempts(3)
                        .initialBackoff(Duration.ofMillis(1))
                        .maxBackoff(Duration.ofMillis(2));
        if (force) builder.retryOn(error -> true);
        return builder.build();
    }

    private static ToolUseBlock call(String id) {
        return ToolUseBlock.builder().id(id).name("probe").input(Map.of()).content("{}").build();
    }

    private static ToolResultBlock run(AgentTool tool, ExecutionConfig config) {
        var toolkit = new Toolkit();
        toolkit.registerTool(tool);
        return toolkit.callTools(List.of(call("op-1")), config, null, null)
                .block(Duration.ofSeconds(5))
                .get(0);
    }

    private static String text(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }

    private static ToolBase tool(
            ToolRetrySafety safety,
            java.util.function.Function<ToolCallParam, Mono<ToolResultBlock>> body) {
        return new ToolBase(
                ToolBase.builder()
                        .name("probe")
                        .description("test")
                        .inputSchema(Map.of("type", "object", "properties", Map.of()))
                        .retrySafety(safety)) {
            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return body.apply(param);
            }
        };
    }

    @Test
    void unknownSideEffectIsNeverRepeatedEvenWithPermissivePredicate() {
        var mutations = new AtomicInteger();
        var result =
                run(
                        tool(
                                ToolRetrySafety.NEVER,
                                param -> {
                                    mutations.incrementAndGet();
                                    return Mono.error(new IOException("reply lost after mutation"));
                                }),
                        config(true));
        assertEquals(1, mutations.get());
        assertTrue(text(result).contains("reply lost"));
        assertEquals("op-1", result.getId());
    }

    @Test
    void readOnlyTransientFailureRetriesFreshInvocation() {
        var attempts = new AtomicInteger();
        var result =
                run(
                        tool(
                                ToolRetrySafety.READ_ONLY,
                                param ->
                                        attempts.incrementAndGet() < 3
                                                ? Mono.error(new IOException("temporary"))
                                                : Mono.just(ToolResultBlock.text("ok"))),
                        config(false));
        assertEquals(3, attempts.get());
        assertEquals("ok", text(result));
    }

    @Test
    void synchronousInvocationFailureIsDeferredAndRetried() {
        var attempts = new AtomicInteger();
        var result =
                run(
                        tool(
                                ToolRetrySafety.READ_ONLY,
                                param -> {
                                    if (attempts.incrementAndGet() == 1)
                                        throw new RuntimeException(new IOException("temporary"));
                                    return Mono.just(ToolResultBlock.text("ok"));
                                }),
                        config(false));
        assertEquals(2, attempts.get());
        assertEquals("ok", text(result));
    }

    @Test
    void permanentFailuresCannotBeOverridden() {
        for (Throwable failure :
                List.of(
                        new SecurityException("denied"),
                        new IllegalArgumentException("invalid"),
                        new CancellationException("cancel"),
                        new InterruptedException("interrupted"),
                        new io.agentscope.core.model.transport.HttpTransportException(
                                "forbidden", 403, ""))) {
            var attempts = new AtomicInteger();
            run(
                    tool(
                            ToolRetrySafety.IDEMPOTENT,
                            param -> {
                                attempts.incrementAndGet();
                                return Mono.error(new RuntimeException(failure));
                            }),
                    config(true));
            assertEquals(1, attempts.get(), failure.toString());
        }
    }

    @Test
    void sameAttemptKeepsOperationIdAndDistinctCallsRemainDistinct() {
        List<String> ids = new ArrayList<>();
        var toolkit = new Toolkit();
        toolkit.registerTool(
                tool(
                        ToolRetrySafety.IDEMPOTENT,
                        param -> {
                            ids.add(param.getToolUseBlock().getId());
                            return ids.size() == 1
                                    ? Mono.error(new IOException("lost"))
                                    : Mono.just(ToolResultBlock.text("ok"));
                        }));
        for (String id : List.of("op-1", "op-2")) {
            var result =
                    toolkit.callTools(List.of(call(id)), config(false), null, null)
                            .block(Duration.ofSeconds(5));
            assertEquals("ok", text(result.get(0)));
        }
        assertEquals(List.of("op-1", "op-1", "op-2"), ids);
    }

    @Test
    void unknownFailureDoesNotRetryByDefault() {
        var attempts = new AtomicInteger();
        run(
                tool(
                        ToolRetrySafety.READ_ONLY,
                        param -> {
                            attempts.incrementAndGet();
                            return Mono.error(new IllegalStateException("bug"));
                        }),
                config(false));
        assertEquals(1, attempts.get());
    }

    @Test
    void readOnlyRemoteAnnotationDoesNotAuthorizeRetries() {
        var remote =
                new ToolBase(
                        ToolBase.builder()
                                .name("probe")
                                .description("remote")
                                .inputSchema(Map.of())
                                .readOnly(true)
                                .mcp("untrusted")) {
                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.empty();
                    }
                };
        assertEquals(ToolRetrySafety.NEVER, remote.getRetrySafety());
    }

    @Test
    void exhaustedRetriesReturnErrorWithOriginalCallMetadata() {
        var attempts = new AtomicInteger();
        var result =
                run(
                        tool(
                                ToolRetrySafety.READ_ONLY,
                                param -> {
                                    attempts.incrementAndGet();
                                    return Mono.error(new IOException("still unavailable"));
                                }),
                        config(false));
        assertEquals(3, attempts.get());
        assertEquals("op-1", result.getId());
        assertEquals("probe", result.getName());
        assertTrue(text(result).startsWith("Error:"));
    }

    @Test
    void timeoutAfterUnknownSideEffectDoesNotReplay() {
        var attempts = new AtomicInteger();
        var result =
                run(
                        tool(
                                ToolRetrySafety.NEVER,
                                param -> {
                                    attempts.incrementAndGet();
                                    return Mono.never();
                                }),
                        ExecutionConfig.builder()
                                .maxAttempts(3)
                                .timeout(Duration.ofMillis(100))
                                .build());
        assertEquals(1, attempts.get());
        assertTrue(text(result).contains("timeout"));
    }

    public static class AnnotatedProbe {
        final AtomicInteger attempts = new AtomicInteger();

        @Tool(name = "probe", description = "probe", retrySafety = ToolRetrySafety.READ_ONLY)
        public String probe() throws IOException {
            if (attempts.incrementAndGet() < 2) throw new IOException("temporary");
            return "ok";
        }
    }

    @Test
    void reflectiveToolPreservesTransientErrorUntilRetryLayer() {
        var probe = new AnnotatedProbe();
        var toolkit = new Toolkit();
        toolkit.registerTool(probe);
        var result =
                toolkit.callTools(List.of(call("op-1")), config(false), null, null)
                        .block(Duration.ofSeconds(5))
                        .get(0);
        assertEquals(2, probe.attempts.get());
        assertTrue(text(result).contains("ok"));
    }
}
