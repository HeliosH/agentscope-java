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

import com.fasterxml.jackson.databind.ObjectMapper;

/** Terminates a Cube sandbox by backend sandbox id. */
public final class CubeSandboxTerminator {

    private final CubePlatformHttp platform;

    public CubeSandboxTerminator(CubeSandboxClientOptions options) {
        CubeSandboxClientOptions opt = options != null ? options : new CubeSandboxClientOptions();
        this.platform = new CubePlatformHttp(CubeHttpClients.create(opt), new ObjectMapper(), opt);
    }

    public void terminate(String sandboxId) throws Exception {
        if (sandboxId == null || sandboxId.isBlank()) {
            return;
        }
        platform.killSandbox(sandboxId.trim());
    }
}
