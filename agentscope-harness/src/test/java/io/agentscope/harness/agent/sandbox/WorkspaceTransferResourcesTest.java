/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.harness.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class WorkspaceTransferResourcesTest {
    @Test
    void reservesBeforeWritingAndReleasesOnlyAfterDeletion() throws Exception {
        var resources = new WorkspaceTransferResources(1, 10, 4096, 0);
        java.nio.file.Path path;
        try (var spool = resources.spool("spool-quota-", 6)) {
            path = spool.path();
            assertThrows(IllegalStateException.class, () -> resources.spool("over-quota-", 5));
            try (var out = spool.output()) {
                out.write(new byte[6]);
                assertThrows(java.io.IOException.class, () -> out.write(1));
            }
            assertEquals(6, Files.size(path));
            assertThrows(java.io.IOException.class, spool::output);
        }
        assertFalse(Files.exists(path));
        try (var next = resources.spool("spool-reuse-", 10)) {
            assertTrue(Files.exists(next.path()));
        }
    }

    @Test
    void interruptDoesNotLeakAConcurrentTransferSlot() throws Exception {
        var resources = new WorkspaceTransferResources(1, 10, 4096);
        var waiting = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        try (var owner = resources.acquire()) {
            var thread =
                    new Thread(
                            () -> {
                                waiting.countDown();
                                try (var ignored = resources.acquire()) {
                                } catch (InterruptedException expected) {
                                    interrupted.set(true);
                                }
                            });
            thread.start();
            waiting.await();
            thread.interrupt();
            thread.join(2000);
            assertFalse(thread.isAlive());
            assertTrue(interrupted.get());
            owner.close();
        }
        try (var next = resources.acquire()) {
            assertNotNull(next);
        }
    }

    @Test
    void rejectsInvalidOrOverflowingBudgets() {
        assertThrows(
                IllegalArgumentException.class, () -> new WorkspaceTransferResources(0, 1, 4096));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkspaceTransferResources(1, 1, 1024 * 1024 + 1));
        assertThrows(
                ArithmeticException.class,
                () ->
                        WorkspaceTransferResources.archiveLimit(
                                new WorkspaceTransferPolicy(1, 1, Long.MAX_VALUE)));
    }

    @Test
    void concurrentSpoolWaitsForExistingBytesToBeDeleted() throws Exception {
        var resources = new WorkspaceTransferResources(2, 10, 4096, 2000);
        var first = resources.spool("spool-first-", 6);
        var waiting = new CountDownLatch(1);
        var result = new java.util.concurrent.CompletableFuture<Boolean>();
        var thread =
                new Thread(
                        () -> {
                            waiting.countDown();
                            try (var second = resources.spool("spool-second-", 7)) {
                                result.complete(Files.exists(second.path()));
                            } catch (Exception error) {
                                result.completeExceptionally(error);
                            }
                        });
        try {
            thread.start();
            waiting.await();
            first.close();
            assertTrue(result.get(3, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            first.close();
            thread.join(3000);
        }
        assertFalse(thread.isAlive());
    }

    @Test
    void emptySpoolsCannotExhaustFilesystemMetadataDespiteHavingTinyPayloads() throws Exception {
        var resources = new WorkspaceTransferResources(1, 1000, 4096, 0);
        var spools = new java.util.ArrayList<WorkspaceTransferResources.Spool>();
        try {
            for (int i = 0; i < 24; i++) spools.add(resources.spool("empty-spool-", 1));
            assertThrows(
                    WorkspaceTransferResources.CapacityException.class,
                    () -> resources.spool("empty-overflow-", 1));
            spools.remove(0).close();
            try (var reused = resources.spool("empty-reuse-", 1)) {
                assertTrue(Files.exists(reused.path()));
            }
        } finally {
            for (var spool : spools) spool.close();
        }
    }
}
