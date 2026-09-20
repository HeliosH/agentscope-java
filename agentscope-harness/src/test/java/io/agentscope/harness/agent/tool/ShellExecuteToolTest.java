/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.model.ShellExecutionRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs({OS.LINUX, OS.MAC})
class ShellExecuteToolTest {

    @TempDir Path workspace;

    @Test
    void nativeCwdTreatsShellSyntaxAsDirectoryData() throws Exception {
        String directory = "space ' ; touch PWNED #\nnext";
        Path target = Files.createDirectory(workspace.resolve(directory));
        var tool = new ShellExecuteTool(new LocalFilesystemWithShell(workspace));
        String output = tool.execute(RuntimeContext.empty(), "pwd", directory, 3);
        assertTrue(output.startsWith("Exit code: 0"));
        assertTrue(output.contains(target.toRealPath().toString()));
        assertFalse(Files.exists(workspace.resolve("PWNED")));
    }

    @Test
    void legacyProviderAdapterQuotesCwdBeforeRunningShell() throws Exception {
        String directory = "-dir ' ; touch PWNED #";
        Path target = Files.createDirectory(workspace.resolve(directory));
        var fs = new LocalFilesystemWithShell(workspace);
        var request = new ShellExecutionRequest("pwd", directory, 3);
        var result = fs.execute(RuntimeContext.empty(), request.legacyShellCommand(), 3);
        assertEquals(0, result.exitCode());
        assertEquals(target.toRealPath().toString(), result.output().trim());
        assertFalse(Files.exists(workspace.resolve("PWNED")));
    }

    @Test
    void cwdCannotEscapeLexicallyOrThroughSymlink(@TempDir Path outside) throws Exception {
        var tool = new ShellExecuteTool(new LocalFilesystemWithShell(workspace));
        for (String directory : new String[] {"..", "a/../../b", "/tmp", "C:\\Windows"}) {
            assertThrows(
                    IllegalArgumentException.class, () -> tool.execute(null, "pwd", directory, 3));
        }
        Files.createSymbolicLink(workspace.resolve("link"), outside);
        assertThrows(SecurityException.class, () -> tool.execute(null, "pwd", "link", 3));
    }
}
