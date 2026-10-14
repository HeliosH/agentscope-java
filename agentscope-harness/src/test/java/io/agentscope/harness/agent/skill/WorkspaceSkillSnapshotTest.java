/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.harness.agent.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolContractSnapshot;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.example.support.InMemorySandboxFilesystemSpec;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.middleware.HarnessSkillMiddleware;
import io.agentscope.harness.agent.sandbox.WorkspaceInputPlan;
import io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan;
import io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan.WorkspaceFile;
import io.agentscope.harness.agent.sandbox.WorkspaceSystemSnapshotStore;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@SuppressWarnings("deprecation")
class WorkspaceSkillSnapshotTest {
    @TempDir Path workspace;

    private static final String DOCUMENT =
            "---\nname: alpha\ndescription: 'Original description'\n---\n\n# Original\n";

    @Test
    void realHarnessLifecycleDiscoversAndLoadsFrozenSkillThroughItsLiveToolkit() {
        var calls = new AtomicInteger();
        var frozen =
                new WorkspaceRestorePlan(
                        "workspace-system://run",
                        "frozen",
                        List.of(
                                file("skills/alpha/SKILL.md", DOCUMENT),
                                file("skills/alpha/guide.md", "frozen guide")));
        var spec = new InMemorySandboxFilesystemSpec();
        spec.systemSnapshotStore((ctx, source, policy) -> frozen);
        Model model =
                new Model() {
                    @Override
                    public String getModelName() {
                        return "frozen-skill-fixture";
                    }

                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        if (calls.getAndIncrement() == 0) {
                            assertTrue(
                                    messages.stream()
                                            .anyMatch(
                                                    m ->
                                                            m.getRole() == MsgRole.SYSTEM
                                                                    && m.getTextContent()
                                                                            .contains(
                                                                                    "alpha_workspace-namespaced")));
                            assertTrue(
                                    tools.stream()
                                            .anyMatch(
                                                    t ->
                                                            t.getName()
                                                                    .equals(
                                                                            "load_skill_through_path")));
                            var use =
                                    ToolUseBlock.builder()
                                            .id("load-frozen")
                                            .name("load_skill_through_path")
                                            .content(
                                                    "{\"skillId\":\"alpha_workspace-namespaced\",\"path\":\"guide.md\"}")
                                            .input(
                                                    Map.of(
                                                            "skillId",
                                                            "alpha_workspace-namespaced",
                                                            "path",
                                                            "guide.md"))
                                            .build();
                            return Flux.just(
                                    new ChatResponse(
                                            "load", List.of(use), null, Map.of(), "tool_use"));
                        }
                        assertTrue(
                                messages.stream()
                                        .flatMap(m -> m.getContent().stream())
                                        .filter(ToolResultBlock.class::isInstance)
                                        .map(ToolResultBlock.class::cast)
                                        .anyMatch(r -> text(r).contains("frozen guide")),
                                () ->
                                        messages.stream()
                                                .flatMap(
                                                        m ->
                                                                m
                                                                        .getContentBlocks(
                                                                                ToolResultBlock
                                                                                        .class)
                                                                        .stream())
                                                .map(WorkspaceSkillSnapshotTest::text)
                                                .toList()
                                                .toString());
                        return Flux.just(
                                new ChatResponse(
                                        "done",
                                        List.of(TextBlock.builder().text("done").build()),
                                        null,
                                        Map.of(),
                                        "stop"));
                    }
                };
        var agent =
                HarnessAgent.builder()
                        .name("snapshot-runtime")
                        .model(model)
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore())
                        .disableMemoryHooks()
                        .filesystem(spec)
                        .build();
        var ctx = RuntimeContext.builder().userId("user").sessionId("session").build();
        ctx.put(
                io.agentscope.harness.agent.sandbox.SandboxIsolationOverride.class,
                new io.agentscope.harness.agent.sandbox.SandboxIsolationOverride(
                        "run/snapshot-test"));
        ctx.put(
                WorkspaceInputPlan.class,
                new WorkspaceInputPlan(
                        new WorkspaceRestorePlan("workspace-input://task", "empty", List.of())));
        assertEquals(
                "done",
                agent.call(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .textContent("load the guide")
                                        .build(),
                                ctx)
                        .block()
                        .getTextContent());
        assertEquals(2, calls.get());
    }

    @Test
    void immutableCatalogAndLazyBytesSurviveWorkspaceEditsAndReconstruction() throws Exception {
        var reads = new AtomicInteger();
        byte[] guide = "original guide".getBytes(StandardCharsets.UTF_8);
        var descriptor = new WorkspaceFile("skills/alpha/references/guide.md", guide);
        var resource =
                new WorkspaceFile(
                        descriptor.path(),
                        descriptor.size(),
                        descriptor.sha256(),
                        () -> {
                            assertFalse(Schedulers.isInNonBlockingThread());
                            reads.incrementAndGet();
                            return new ByteArrayInputStream(guide);
                        });
        var ctx = context(List.of(file("skills/alpha/SKILL.md", DOCUMENT), resource));
        Files.createDirectories(workspace.resolve("skills/alpha/references"));
        Files.writeString(
                workspace.resolve("skills/alpha/SKILL.md"),
                DOCUMENT.replace("Original", "Changed"));
        Files.writeString(workspace.resolve("skills/alpha/references/guide.md"), "changed guide");
        for (int restart = 0; restart < 2; restart++) {
            var middleware = middleware();
            String prompt = middleware.onSystemPrompt(null, ctx, "base").block();
            assertTrue(prompt.contains("Original description"));
            assertFalse(prompt.contains("Changed"));
            assertEquals(restart, reads.get(), "resource content must stay lazy during discovery");
            var tool = middleware.runtime().loadTool();
            assertTrue(text(tool.callAsync(param("SKILL.md")).block()).contains(DOCUMENT));
            assertTrue(
                    text(tool.callAsync(param("references/guide.md"))
                                    .subscribeOn(Schedulers.parallel())
                                    .block())
                            .contains("original guide"));
        }
        assertEquals(2, reads.get());
    }

    @Test
    void resourceDigestChangesContractEvenWhenSkillDocumentAndIdsAreUnchanged() {
        var first = middleware();
        var second = middleware();
        first.onSystemPrompt(
                        null,
                        context(
                                List.of(
                                        file("skills/alpha/SKILL.md", DOCUMENT),
                                        file("skills/alpha/script.py", "v1"))),
                        "base")
                .block();
        second.onSystemPrompt(
                        null,
                        context(
                                List.of(
                                        file("skills/alpha/SKILL.md", DOCUMENT),
                                        file("skills/alpha/script.py", "v2"))),
                        "base")
                .block();
        var expected = ToolContractSnapshot.capture(first.runtime().loadTool(), Map.of());
        assertNotEquals(
                expected.hash(),
                ToolContractSnapshot.capture(second.runtime().loadTool(), Map.of()).hash());
        var reconstructed = middleware();
        reconstructed
                .onSystemPrompt(
                        null,
                        context(
                                List.of(
                                        file("skills/alpha/SKILL.md", DOCUMENT),
                                        file("skills/alpha/script.py", "v1"))),
                        "base")
                .block();
        assertEquals(
                expected.hash(),
                ToolContractSnapshot.capture(reconstructed.runtime().loadTool(), Map.of()).hash());
    }

    @Test
    void admittedAsyncLoadKeepsItsCatalogWhenAnotherCallInstallsDifferentBytes() {
        var middleware = middleware();
        middleware
                .onSystemPrompt(
                        null,
                        context(
                                List.of(
                                        file("skills/alpha/SKILL.md", DOCUMENT),
                                        file("skills/alpha/a.md", "first run"))),
                        "base")
                .block();
        var admitted = middleware.runtime().loadTool().callAsync(param("a.md"));
        middleware
                .onSystemPrompt(
                        null,
                        context(
                                List.of(
                                        file("skills/alpha/SKILL.md", DOCUMENT),
                                        file("skills/alpha/a.md", "second run"))),
                        "base")
                .block();
        assertTrue(text(admitted.block()).contains("first run"));
        assertTrue(
                text(middleware.runtime().loadTool().callAsync(param("a.md")).block())
                        .contains("second run"));
    }

    @Test
    void emptyFrozenManifestDoesNotFallBackToNewWorkspaceSkills() throws Exception {
        Files.createDirectories(workspace.resolve("skills/alpha"));
        Files.writeString(workspace.resolve("skills/alpha/SKILL.md"), DOCUMENT);
        var middleware = middleware();
        assertEquals("base", middleware.onSystemPrompt(null, context(List.of()), "base").block());
        assertTrue(middleware.runtime().currentCatalog().isEmpty());
        assertTrue(
                text(middleware.runtime().loadTool().callAsync(param("SKILL.md")).block())
                        .contains("Skill not found"));
        // Existing unmanaged applications retain dynamic workspace discovery.
        assertTrue(
                middleware
                        .onSystemPrompt(null, RuntimeContext.empty(), "base")
                        .block()
                        .contains("alpha_workspace"));
    }

    @Test
    void filteredSkillCannotBeLoadedByGuessingItsId() {
        var ctx = context(List.of(file("skills/alpha/SKILL.md", DOCUMENT)));
        var middleware = middleware();
        middleware.onSystemPrompt(null, ctx, "base").block();
        ctx.put(SkillFilter.class, SkillFilter.none());
        assertEquals("base", middleware.onSystemPrompt(null, ctx, "base").block());
        assertTrue(
                text(middleware.runtime().loadTool().callAsync(param("SKILL.md")).block())
                        .contains("Skill not found"));
    }

    @Test
    void revokedOrCorruptObjectsCannotReturnOldCatalogOrSkillDocument() {
        var revoked = new AtomicBoolean();
        var original = file("skills/alpha/SKILL.md", DOCUMENT);
        var stored =
                new WorkspaceFile(
                        original.path(),
                        original.size(),
                        original.sha256(),
                        () -> {
                            if (revoked.get()) throw new IllegalStateException("revoked");
                            return new ByteArrayInputStream(
                                    DOCUMENT.getBytes(StandardCharsets.UTF_8));
                        });
        var middleware = middleware();
        var ctx = context(List.of(stored));
        middleware.onSystemPrompt(null, ctx, "base").block();
        revoked.set(true);
        assertFalse(
                text(middleware.runtime().loadTool().callAsync(param("SKILL.md")).block())
                        .contains("Successfully loaded"));
        assertThrows(
                RuntimeException.class, () -> middleware.onSystemPrompt(null, ctx, "base").block());
        assertTrue(middleware.runtime().currentCatalog().isEmpty());
        var corrupted =
                new WorkspaceFile(
                        original.path(),
                        original.size(),
                        original.sha256(),
                        () -> new ByteArrayInputStream(new byte[(int) original.size()]));
        assertThrows(
                RuntimeException.class,
                () -> middleware.onSystemPrompt(null, context(List.of(corrupted)), "base").block());
    }

    @Test
    void exactResourcePathsRejectTraversalAndNewFiles() {
        var repo = repository();
        var ctx =
                context(
                        List.of(
                                file("skills/alpha/SKILL.md", DOCUMENT),
                                file("skills/alpha/a.md", "a")));
        var resources = repo.resourcesFor("alpha", ctx);
        for (String path :
                List.of("../SKILL.md", "/a.md", "./a.md", "x/../a.md", "x\\a.md", "new.md"))
            assertTrue(resources.read(path).isEmpty(), path);
        assertEquals(List.of("SKILL.md", "a.md"), resources.list());
        assertEquals("a", resources.read("a.md").orElseThrow());
    }

    @Test
    void nameMismatchAndDuplicatePathsFailClosedAndDraftsAreHidden() {
        var middleware = middleware();
        var ctx = context(List.of(file("skills/other/SKILL.md", DOCUMENT)));
        assertThrows(
                RuntimeException.class, () -> middleware.onSystemPrompt(null, ctx, "base").block());
        var duplicate = file("skills/alpha/SKILL.md", DOCUMENT);
        assertThrows(
                RuntimeException.class,
                () ->
                        middleware
                                .onSystemPrompt(
                                        null, context(List.of(duplicate, duplicate)), "base")
                                .block());
        assertEquals(
                "base",
                middleware
                        .onSystemPrompt(
                                null,
                                context(List.of(file("skills/_drafts/alpha/SKILL.md", DOCUMENT))),
                                "base")
                        .block());
    }

    private WorkspaceSkillRepository repository() {
        // A different supplier context must not override the actual invocation context.
        return new WorkspaceSkillRepository(
                new LocalFilesystem(workspace), "skills", RuntimeContext::empty);
    }

    private HarnessSkillMiddleware middleware() {
        return new HarnessSkillMiddleware(List.of(repository()), null);
    }

    private static WorkspaceFile file(String path, String value) {
        return new WorkspaceFile(path, value.getBytes(StandardCharsets.UTF_8));
    }

    private static RuntimeContext context(List<WorkspaceFile> files) {
        var ctx = RuntimeContext.empty();
        ctx.put(
                WorkspaceSystemSnapshotStore.Plan.class,
                new WorkspaceSystemSnapshotStore.Plan(
                        new WorkspaceRestorePlan(
                                "workspace-system://run", "same-version-label", files)));
        return ctx;
    }

    private static ToolCallParam param(String path) {
        return ToolCallParam.builder()
                .input(Map.of("skillId", "alpha_workspace", "path", path))
                .build();
    }

    private static String text(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .reduce("", String::concat);
    }
}
