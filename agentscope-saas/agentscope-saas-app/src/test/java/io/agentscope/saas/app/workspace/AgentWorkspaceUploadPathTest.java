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
package io.agentscope.saas.app.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentWorkspaceUploadPathTest {

    @Test
    void defaultUploadPathIsIsolatedUnderInputs() {
        assertThat(AgentWorkspaceController.uploadTargetPath(null, "quarterly report.pdf"))
                .isEqualTo("inputs/quarterly report.pdf");
    }

    @Test
    void explicitUploadDirectoryRetainsCompatibility() {
        assertThat(AgentWorkspaceController.uploadTargetPath("inputs/reports/", "source.csv"))
                .isEqualTo("inputs/reports/source.csv");
    }
}
