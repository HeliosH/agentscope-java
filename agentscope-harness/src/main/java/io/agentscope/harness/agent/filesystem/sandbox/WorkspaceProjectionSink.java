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
package io.agentscope.harness.agent.filesystem.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.WorkspaceProjectionPlan;
import io.agentscope.harness.agent.sandbox.WorkspaceProjectionReport;

/**
 * Receives release-time sandbox workspace projection events.
 *
 * <p>Admission/completion callbacks are critical projection barriers. Per-file callbacks remain
 * best-effort so callers can collect catalog failures without preventing sandbox cleanup.
 */
public interface WorkspaceProjectionSink {

    /** The validated immutable capture, before any individual projection writes. */
    default void onProjectionCaptured(
            RuntimeContext context, WorkspaceProjectionPlan plan, java.nio.file.Path snapshot)
            throws Exception {}

    /** Critical admission barrier: failure prevents all release-time file transfers. */
    default void onProjectionPlanned(RuntimeContext context, WorkspaceProjectionPlan plan)
            throws Exception {}

    /** Records a complete transfer scan, not an assertion that every catalog write succeeded. */
    default void onProjectionCompleted(RuntimeContext context, WorkspaceProjectionReport report)
            throws Exception {}

    static WorkspaceProjectionSink noop() {
        return Noop.INSTANCE;
    }

    default void onProjectedFile(RuntimeContext runtimeContext, String path, byte[] content) {}

    /** Opt in only when the catalog is also the authoritative read path for this execution. */
    default boolean supportsStreamProjection(RuntimeContext context) {
        return false;
    }

    default void onProjectedFile(
            RuntimeContext context,
            String path,
            java.nio.file.Path content,
            long size,
            String sha256)
            throws Exception {
        throw new UnsupportedOperationException("Streaming projection is not configured");
    }

    default void onDeletedFile(RuntimeContext runtimeContext, String path) {}

    enum Noop implements WorkspaceProjectionSink {
        INSTANCE
    }
}
