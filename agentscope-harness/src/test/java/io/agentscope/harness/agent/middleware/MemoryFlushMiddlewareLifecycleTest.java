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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class MemoryFlushMiddlewareLifecycleTest {

    @Test
    void waitsForMemoryOffloadBeforeAgentStreamCompletes() {
        Sinks.Empty<Void> flush = Sinks.empty();
        AtomicBoolean completed = new AtomicBoolean();
        MemoryFlushMiddleware middleware =
                new MemoryFlushMiddleware(null, null) {
                    @Override
                    Mono<Void> doFlush(Agent agent, RuntimeContext context) {
                        return flush.asMono();
                    }
                };

        middleware
                .onAgent(null, RuntimeContext.empty(), null, input -> Flux.empty())
                .doOnComplete(() -> completed.set(true))
                .subscribe();

        assertFalse(completed.get());
        flush.tryEmitEmpty();
        assertTrue(completed.get());
    }
}
