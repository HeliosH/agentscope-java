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
package io.agentscope.core.tool;

/** Trusted local declaration of whether an automatic retry may repeat a tool invocation. */
public enum ToolRetrySafety {
    /** Default for existing tools and tools with unknown side effects. */
    NEVER,
    /** Repeating the call has no observable side effects. */
    READ_ONLY,
    /** The implementation guarantees repeated attempts with the same call ID are idempotent. */
    IDEMPOTENT
}
