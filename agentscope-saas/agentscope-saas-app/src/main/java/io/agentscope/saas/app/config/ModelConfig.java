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

import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.saas.model.StubChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Produces the {@link Model} bean according to {@code saas.model.type}:
 *
 * <ul>
 *   <li>{@code stub} — zero-dependency echo model (local smoke testing)</li>
 *   <li>{@code gateway} — OpenAI-compatible internal model gateway (recommended for production; see
 *       {@code docs/enterprise-platform-java/07-model-gateway.md})</li>
 *   <li>{@code dashscope} — Alibaba DashScope (Qwen) direct</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(SaasProperties.class)
public class ModelConfig {

    private static final Logger log = LoggerFactory.getLogger(ModelConfig.class);

    @Bean
    public Model chatModel(SaasProperties properties) {
        SaasProperties.Model cfg = properties.getModel();
        String type = cfg.getType() == null ? "stub" : cfg.getType().toLowerCase();
        switch (type) {
            case "gateway" -> {
                log.info(
                        "Using OpenAI-compatible model gateway: baseUrl={} model={}",
                        cfg.getBaseUrl(),
                        cfg.getName());
                return OpenAIChatModel.builder()
                        .apiKey(cfg.getApiKey())
                        .baseUrl(cfg.getBaseUrl())
                        .modelName(cfg.getName())
                        .stream(true)
                        .build();
            }
            case "dashscope" -> {
                log.info("Using DashScope model: {}", cfg.getName());
                return DashScopeChatModel.builder()
                        .apiKey(cfg.getApiKey())
                        .modelName(cfg.getName())
                        .stream(true)
                        .build();
            }
            default -> {
                log.info("Using stub echo model (no external LLM)");
                return new StubChatModel();
            }
        }
    }
}
