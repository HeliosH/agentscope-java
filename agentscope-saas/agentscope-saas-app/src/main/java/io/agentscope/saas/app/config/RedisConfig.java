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
package io.agentscope.saas.app.config;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.extensions.redis.sandbox.RedisSandboxExecutionGuard;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.agentscope.extensions.redis.store.RedisStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.saas.core.ratelimit.InMemoryRateLimiter;
import io.agentscope.saas.core.ratelimit.RateLimiter;
import io.agentscope.saas.core.ratelimit.RedisRateLimiter;
import io.agentscope.saas.sandbox.MeteredSandboxExecutionGuard;
import io.agentscope.saas.sandbox.SandboxMetrics;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.UnifiedJedis;

/**
 * Wires the session/state store and rate limiter. When {@code saas.redis.enabled=true} (default
 * production), a single shared Valkey/Redis client backs both the {@link AgentStateStore} and the
 * {@link RateLimiter} so sessions and limits are coordinated across replicas. When disabled (local
 * profile), in-memory implementations are used so the app runs with zero external dependencies.
 */
@Configuration
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    /** Shared Redis client, created only when Redis is enabled. */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "saas.redis", name = "enabled", havingValue = "true")
    public UnifiedJedis unifiedJedis(SaasProperties properties) {
        log.info("Connecting to Redis (Valkey) at {}", properties.getRedis().getUri());
        return new UnifiedJedis(properties.getRedis().getUri());
    }

    /**
     * Redis-backed {@link BaseStore} that backs {@code RemoteFilesystemSpec} when the sandbox is off.
     * Gives each user an isolated workspace (MEMORY.md, skills/, memory/, ...) without Docker/FUSE,
     * namespaced by {@code IsolationScope.USER}. Only present when Redis is enabled; absent otherwise
     * so {@code AgentConfig} falls back to a shell-less, filesystem-less agent (the local-profile
     * behavior).
     */
    @Bean
    @ConditionalOnProperty(prefix = "saas.redis", name = "enabled", havingValue = "true")
    public BaseStore workspaceBaseStore(SaasProperties properties, UnifiedJedis jedis) {
        log.info("Using Redis-backed workspace store (per-user workspaces without Docker)");
        return new RedisStore(jedis, properties.getRedis().getKeyPrefix() + "store:");
    }

    @Bean(destroyMethod = "")
    public AgentStateStore agentStateStore(
            SaasProperties properties, ObjectProvider<UnifiedJedis> jedisProvider) {
        SaasProperties.Redis redis = properties.getRedis();
        UnifiedJedis jedis = jedisProvider.getIfAvailable();
        if (redis.isEnabled() && jedis != null) {
            log.info("Using Redis-backed agent state store");
            return RedisAgentStateStore.builder()
                    .jedisClient(jedis)
                    .keyPrefix(redis.getKeyPrefix() + "session:")
                    .build();
        }
        log.info("Using in-memory agent state store (no Redis)");
        return new InMemoryAgentStateStore();
    }

    @Bean
    public RateLimiter rateLimiter(
            SaasProperties properties, ObjectProvider<UnifiedJedis> jedisProvider) {
        SaasProperties.Redis redis = properties.getRedis();
        UnifiedJedis jedis = jedisProvider.getIfAvailable();
        if (redis.isEnabled() && jedis != null) {
            return new RedisRateLimiter(jedis, redis.getKeyPrefix() + "ratelimit:");
        }
        return new InMemoryRateLimiter();
    }

    /**
     * Redis-backed distributed lock that serialises concurrent sandbox access for the same isolation
     * slot. Required for multi-replica deployments so that two instances never resume the same
     * sandbox state simultaneously. When Redis is disabled (local profile), no guard bean exists and
     * the framework falls back to {@code SandboxExecutionGuard.noop()}.
     */
    @Bean
    @ConditionalOnProperty(prefix = "saas.redis", name = "enabled", havingValue = "true")
    public SandboxExecutionGuard sandboxExecutionGuard(
            SaasProperties properties, UnifiedJedis jedis, SandboxMetrics sandboxMetrics) {
        SaasProperties.Sandbox sandbox = properties.getSandbox();
        RedisSandboxExecutionGuard.Builder builder =
                RedisSandboxExecutionGuard.builder(jedis)
                        .keyPrefix(properties.getRedis().getKeyPrefix() + "sandbox:lock:")
                        .leaseTtl(
                                Duration.ofSeconds(
                                        Math.max(1, sandbox.getExecutionGuardLeaseTtlSeconds())))
                        .retryInterval(
                                Duration.ofMillis(
                                        Math.max(
                                                1,
                                                sandbox.getExecutionGuardRetryIntervalMillis())));
        if (sandbox.getExecutionGuardMaxWaitSeconds() > 0) {
            builder.maxWait(Duration.ofSeconds(sandbox.getExecutionGuardMaxWaitSeconds()));
        }
        log.info(
                "Creating Redis-backed sandbox execution guard (leaseTtl={}s retry={}ms"
                        + " maxWait={}s)",
                Math.max(1, sandbox.getExecutionGuardLeaseTtlSeconds()),
                Math.max(1, sandbox.getExecutionGuardRetryIntervalMillis()),
                sandbox.getExecutionGuardMaxWaitSeconds() > 0
                        ? sandbox.getExecutionGuardMaxWaitSeconds()
                        : "unbounded");
        return new MeteredSandboxExecutionGuard(builder.build(), sandbox.getType(), sandboxMetrics);
    }
}
