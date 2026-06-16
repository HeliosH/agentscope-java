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
package io.agentscope.saas.core.ratelimit;

/**
 * Sliding-window rate limiter abstraction. Phase 1 ships an in-memory implementation suitable for a
 * single replica; a Redis (Valkey) backed implementation can be dropped in for multi-replica
 * deployments without touching the middleware.
 */
public interface RateLimiter {

    /**
     * Attempt to consume one unit against the given key's window.
     *
     * @param key the bucket key (typically the org id)
     * @param maxRequests max requests permitted within the window
     * @param windowSeconds window length in seconds
     * @return {@code true} if allowed, {@code false} if the limit is exceeded
     */
    boolean tryAcquire(String key, int maxRequests, int windowSeconds);
}
