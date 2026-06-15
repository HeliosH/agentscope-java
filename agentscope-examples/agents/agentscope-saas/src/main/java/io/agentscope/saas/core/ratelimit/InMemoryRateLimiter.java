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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple in-memory fixed-window rate limiter for single-replica deployments and local testing.
 * Buckets are keyed by {@code key + ":" + windowIndex}; stale buckets are lazily overwritten as the
 * window advances.
 */
public class InMemoryRateLimiter implements RateLimiter {

    private final ConcurrentHashMap<String, AtomicInteger> buckets = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String key, int maxRequests, int windowSeconds) {
        if (maxRequests <= 0 || windowSeconds <= 0) {
            return true; // unlimited
        }
        long windowIndex = (System.currentTimeMillis() / 1000L) / windowSeconds;
        String bucketKey = key + ":" + windowIndex;
        AtomicInteger counter = buckets.computeIfAbsent(bucketKey, k -> new AtomicInteger(0));
        // Opportunistic cleanup of old buckets to bound memory.
        if (buckets.size() > 10_000) {
            buckets.keySet().removeIf(k -> !k.endsWith(":" + windowIndex));
        }
        return counter.incrementAndGet() <= maxRequests;
    }
}
