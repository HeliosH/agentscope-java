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
package io.agentscope.harness.agent.sandbox;

import java.time.Duration;

/** Raised when a sandbox execution guard cannot acquire a slot within the configured wait budget. */
public class SandboxExecutionTimeoutException extends RuntimeException {

    private final SandboxIsolationKey isolationKey;
    private final Duration maxWait;

    public SandboxExecutionTimeoutException(SandboxIsolationKey isolationKey, Duration maxWait) {
        super(
                "Timed out waiting for sandbox execution slot"
                        + (isolationKey != null ? " for " + isolationKey : "")
                        + (maxWait != null ? " after " + maxWait : ""));
        this.isolationKey = isolationKey;
        this.maxWait = maxWait;
    }

    public SandboxIsolationKey getIsolationKey() {
        return isolationKey;
    }

    public Duration getMaxWait() {
        return maxWait;
    }
}
