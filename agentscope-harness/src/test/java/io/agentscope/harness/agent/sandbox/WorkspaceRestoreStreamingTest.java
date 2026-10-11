/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.harness.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkspaceRestoreStreamingTest {
    private static final byte[] BYTES = new byte[5 * 1024 * 1024];

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void descriptorDoesNotOpenOrMaterializeTheWorkingSet() throws Exception {
        var opens = new AtomicInteger();
        var file =
                new WorkspaceRestorePlan.WorkspaceFile(
                        "inputs/data.bin",
                        BYTES.length,
                        sha(BYTES),
                        () -> {
                            opens.incrementAndGet();
                            return new ByteArrayInputStream(BYTES);
                        });
        assertEquals(0, opens.get());
        file.transferTo(OutputStream.nullOutputStream(), 4096);
        assertEquals(1, opens.get());
        assertEquals(sha(BYTES), file.sha256());
    }

    @Test
    void rejectsTruncationOversizeAndWrongDigest() throws Exception {
        String sha = sha(new byte[1]);
        for (int actual : new int[] {0, 2}) {
            var file =
                    new WorkspaceRestorePlan.WorkspaceFile(
                            "outputs/data.bin",
                            1,
                            sha,
                            () -> new ByteArrayInputStream(new byte[actual]));
            assertThrows(
                    java.io.IOException.class,
                    () -> file.transferTo(OutputStream.nullOutputStream(), 4096));
        }
        var wrong =
                new WorkspaceRestorePlan.WorkspaceFile(
                        "outputs/data.bin",
                        1,
                        "0".repeat(64),
                        () -> new ByteArrayInputStream(new byte[1]));
        assertThrows(
                java.io.IOException.class,
                () -> wrong.transferTo(OutputStream.nullOutputStream(), 4096));
    }

    @Test
    void largeDescriptorsCannotUseTheByteArrayCompatibilityPath() {
        var file =
                new WorkspaceRestorePlan.WorkspaceFile(
                        "inputs/large.bin",
                        64L * 1024 * 1024,
                        "0".repeat(64),
                        () -> {
                            throw new AssertionError("Must not open");
                        });
        assertThrows(IllegalStateException.class, file::content);
    }
}
