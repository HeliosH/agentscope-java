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
package io.agentscope.saas.sandbox;

import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxExecutionTimeoutException;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import java.util.concurrent.atomic.AtomicBoolean;

/** Adds low-cardinality queue/backpressure metrics around a sandbox execution guard. */
public final class MeteredSandboxExecutionGuard implements SandboxExecutionGuard {

    private final SandboxExecutionGuard delegate;
    private final String sandboxType;
    private final SandboxMetrics metrics;

    public MeteredSandboxExecutionGuard(
            SandboxExecutionGuard delegate, String sandboxType, SandboxMetrics metrics) {
        this.delegate = delegate != null ? delegate : SandboxExecutionGuard.noop();
        this.sandboxType = sandboxType;
        this.metrics = metrics != null ? metrics : SandboxMetrics.noop();
    }

    @Override
    public SandboxLease tryEnter(SandboxIsolationKey key) throws InterruptedException {
        String scope = key != null && key.getScope() != null ? key.getScope().name() : null;
        long startedAt = System.nanoTime();
        metrics.incrementQueueDepth(sandboxType, scope);
        try {
            SandboxLease lease = delegate.tryEnter(key);
            metrics.recordQueueWait(sandboxType, scope, "acquired", System.nanoTime() - startedAt);
            metrics.incrementActiveExecution(sandboxType, scope);
            return new MeteredLease(
                    lease, () -> metrics.decrementActiveExecution(sandboxType, scope));
        } catch (SandboxExecutionTimeoutException e) {
            metrics.recordQueueWait(sandboxType, scope, "timeout", System.nanoTime() - startedAt);
            metrics.recordQueueTimeout(sandboxType, scope);
            throw e;
        } catch (InterruptedException e) {
            metrics.recordQueueWait(
                    sandboxType, scope, "interrupted", System.nanoTime() - startedAt);
            throw e;
        } catch (RuntimeException e) {
            metrics.recordQueueWait(sandboxType, scope, "error", System.nanoTime() - startedAt);
            throw e;
        } finally {
            metrics.decrementQueueDepth(sandboxType, scope);
        }
    }

    private static final class MeteredLease implements SandboxLease {

        private final SandboxLease delegate;
        private final Runnable onClose;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private MeteredLease(SandboxLease delegate, Runnable onClose) {
            this.delegate = delegate != null ? delegate : SandboxLease.noop();
            this.onClose = onClose;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                delegate.close();
            } finally {
                if (onClose != null) {
                    onClose.run();
                }
            }
        }
    }
}
