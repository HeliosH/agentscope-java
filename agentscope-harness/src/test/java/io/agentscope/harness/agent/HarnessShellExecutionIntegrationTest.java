package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/** Scripted model, real Agent loop, reflective tool, overlay, and host process. */
@EnabledOnOs({OS.LINUX, OS.MAC})
class HarnessShellExecutionIntegrationTest {
    @TempDir Path workspace;

    @Test
    void shellOutputAndLiteralWorkingDirectoryReachNextModelTurn() throws Exception {
        String cwd = "space ' ; touch PWNED #";
        Files.createDirectories(workspace.resolve(cwd));
        String command =
                "pwd; awk 'BEGIN { for (i=0; i<12000; i++) { "
                        + "print \"stdout-data\"; print \"stderr-data\" > \"/dev/stderr\" } "
                        + "print \"STDOUT_END\"; print \"STDERR_END\" > \"/dev/stderr\" }'";
        var input =
                Map.<String, Object>of("command", command, "working_directory", cwd, "timeout", 15);
        assertEquals(
                PermissionBehavior.PASSTHROUGH,
                io.agentscope.core.permission.ToolInputSecurityGuard.inspect("execute", input)
                        .getBehavior());
        var call =
                ToolUseBlock.builder()
                        .name("execute")
                        .id("shell-1")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();
        var captured = new AtomicReference<String>();
        var turns = new AtomicInteger();
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("scripted-shell-integration");
        when(model.stream(anyList(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            if (turns.getAndIncrement() == 0) {
                                return Flux.just(
                                        new ChatResponse(
                                                "tool-turn",
                                                List.of(call),
                                                null,
                                                Map.of(),
                                                "tool_use"));
                            }
                            List<Msg> messages = invocation.getArgument(0);
                            captured.set(
                                    messages.stream()
                                            .flatMap(
                                                    message ->
                                                            message
                                                                    .getContentBlocks(
                                                                            ToolResultBlock.class)
                                                                    .stream())
                                            .flatMap(result -> result.getOutput().stream())
                                            .filter(TextBlock.class::isInstance)
                                            .map(TextBlock.class::cast)
                                            .map(TextBlock::getText)
                                            .collect(java.util.stream.Collectors.joining("\n")));
                            return Flux.just(
                                    new ChatResponse(
                                            "done-turn",
                                            List.of(TextBlock.builder().text("done").build()),
                                            null,
                                            Map.of(),
                                            "stop"));
                        });
        var agent =
                HarnessAgent.builder()
                        .name("shell-integration")
                        .model(model)
                        .workspace(workspace)
                        .disableMemoryHooks()
                        .stateStore(new io.agentscope.core.state.InMemoryAgentStateStore())
                        .permissionContext(
                                PermissionContextState.builder()
                                        .addAllowRule(
                                                "execute",
                                                new PermissionRule(
                                                        "execute",
                                                        "^"
                                                                + java.util.regex.Pattern.quote(
                                                                        command)
                                                                + "$",
                                                        PermissionBehavior.ALLOW,
                                                        "integration-test"))
                                        .build())
                        .abstractFilesystem(new LocalFilesystemWithShell(workspace))
                        .build();
        agent.call(Msg.builder().role(MsgRole.USER).textContent("run shell probe").build())
                .block(Duration.ofSeconds(30));
        String history = captured.get();
        assertTrue(history != null && history.contains("Exit code: 0"), String.valueOf(history));
        assertTrue(history.contains("STDOUT_END"));
        assertTrue(history.contains("STDERR_END"));
        assertTrue(history.contains(cwd));
        assertTrue(history.contains("output was truncated"));
        assertFalse(Files.exists(workspace.resolve("PWNED")));
    }
}
