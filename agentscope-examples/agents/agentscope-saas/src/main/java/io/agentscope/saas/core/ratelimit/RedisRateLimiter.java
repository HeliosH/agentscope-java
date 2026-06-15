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

import redis.clients.jedis.UnifiedJedis;

/**
 * Redis (Valkey) backed fixed-window rate limiter for multi-replica deployments. Uses an atomic
 * INCR + EXPIRE on a per-window key so all replicas share the same counter.
 */
public class RedisRateLimiter implements RateLimiter {

    private final UnifiedJedis jedis;
    private final String keyPrefix;

    public RedisRateLimiter(UnifiedJedis jedis, String keyPrefix) {
        this.jedis = jedis;
        this.keyPrefix = keyPrefix == null ? "agentscope:saas:ratelimit:" : keyPrefix;
    }

    @Override
    public boolean tryAcquire(String key, int maxRequests, int windowSeconds) {
        if (maxRequests <= 0 || windowSeconds <= 0) {
            return true; // unlimited
        }
        long windowIndex = (System.currentTimeMillis() / 1000L) / windowSeconds;
        String redisKey = keyPrefix + key + ":" + windowIndex;
        long count = jedis.incr(redisKey);
        if (count == 1L) {
            jedis.expire(redisKey, windowSeconds);
        }
        return count <= maxRequests;
    }
}
