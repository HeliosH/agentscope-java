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
package io.agentscope.core.model;

/** Token limits for one deploy-time configured model route. */
public record ModelContextProfile(
        String modelId, int contextWindowTokens, int maxOutputTokens, int safetyMarginTokens) {

    public ModelContextProfile {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId is required");
        }
        if (contextWindowTokens <= 0) {
            throw new IllegalArgumentException("contextWindowTokens must be positive");
        }
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
        if (safetyMarginTokens < 0) {
            throw new IllegalArgumentException("safetyMarginTokens must not be negative");
        }
        if (maxOutputTokens + safetyMarginTokens >= contextWindowTokens) {
            throw new IllegalArgumentException(
                    "maxOutputTokens plus safetyMarginTokens must be smaller than the context"
                            + " window");
        }
    }

    /** Maximum estimated tokens available to system prompts, tools, and conversation messages. */
    public int inputTokenBudget() {
        return contextWindowTokens - maxOutputTokens - safetyMarginTokens;
    }
}
