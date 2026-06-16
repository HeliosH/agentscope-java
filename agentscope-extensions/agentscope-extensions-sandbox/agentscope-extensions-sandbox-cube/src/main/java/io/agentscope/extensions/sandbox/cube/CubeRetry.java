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
package io.agentscope.extensions.sandbox.cube;

import io.agentscope.harness.agent.sandbox.SandboxException;
import java.io.IOException;
import java.util.concurrent.Callable;

/** Static retry utility for Cube platform API calls. */
final class CubeRetry {

    private CubeRetry() {}

    /**
     * Retries a callable up to {@code maxAttempts} times with linear backoff on transient errors.
     *
     * @param <T>          return type
     * @param maxAttempts  maximum number of attempts (minimum 1)
     * @param callable     the operation to retry
     * @return the result on success
     * @throws Exception on terminal failure
     */
    static <T> T withRetries(int maxAttempts, Callable<T> callable) throws Exception {
        int attempts = Math.max(1, maxAttempts);
        Exception last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                return callable.call();
            } catch (IOException e) {
                last = e;
                if (i < attempts - 1) {
                    sleep((long) (200L * (i + 1)));
                }
            } catch (SandboxException e) {
                if (isRetryable(e) && i < attempts - 1) {
                    last = e;
                    sleep((long) (200L * (i + 1)));
                } else {
                    throw e;
                }
            }
        }
        throw last != null ? last : new RuntimeException("Retry exhausted");
    }

    private static boolean isRetryable(SandboxException e) {
        // Retry on HTTP 408, 429, 502, 503 (check message for HTTP codes)
        String msg = e.getMessage();
        return msg != null
                && (msg.contains("HTTP 408")
                        || msg.contains("HTTP 429")
                        || msg.contains("HTTP 502")
                        || msg.contains("HTTP 503"));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
