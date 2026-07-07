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
package io.agentscope.saas.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.saas.app.marketplace.MarketplaceRegistry;
import io.agentscope.saas.app.marketplace.MarketplacesController;
import io.agentscope.saas.app.observability.AgentRunMetrics;
import io.agentscope.saas.app.tools.AgentToolsController;
import io.agentscope.saas.core.persistence.repo.MarketplaceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the full application context on the {@code local} profile (H2 + Flyway H2 migrations +
 * in-memory/redis-off) to catch wiring + migration regressions that pure unit tests miss. Covers
 * the Phase F5 additions: the {@code marketplaces} table (V8 H2 variant), {@link
 * MarketplaceRepository}/{@link MarketplaceRegistry}/{@link MarketplacesController} bean wiring,
 * and {@link AgentToolsController}.
 */
@SpringBootTest
@ActiveProfiles("local")
class SaasAppContextLoadsTest {

    @Autowired MarketplaceRepository marketplaceRepository;
    @Autowired MarketplaceRegistry marketplaceRegistry;
    @Autowired MarketplacesController marketplacesController;
    @Autowired AgentToolsController agentToolsController;
    @Autowired AgentRunMetrics agentRunMetrics;
    @Autowired MeterRegistry meterRegistry;

    @Test
    void contextLoads() {
        assertThat(marketplaceRepository).isNotNull();
        assertThat(marketplaceRegistry).isNotNull();
        assertThat(marketplacesController).isNotNull();
        assertThat(agentToolsController).isNotNull();
        assertThat(agentRunMetrics).isNotNull();
        assertThat(meterRegistry.find("saas.agent.chat.stream.duration").timer()).isNotNull();
        assertThat(meterRegistry.find("saas.llm.model.calls").counter()).isNotNull();
    }
}
