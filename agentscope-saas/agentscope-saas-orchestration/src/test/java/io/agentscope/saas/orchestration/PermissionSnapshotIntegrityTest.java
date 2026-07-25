/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.saas.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PermissionSnapshotIntegrityTest {

    @Test
    void canonicalHashIsIndependentOfObjectKeyOrderAndWhitespace() {
        var first =
                PermissionSnapshotIntegrity.canonicalize(
                        """
                        {
                          "rules": [{"behavior": "DENY", "tool": "execute"}],
                          "mode": "DEFAULT"
                        }
                        """);
        var second =
                PermissionSnapshotIntegrity.canonicalize(
                        "{\"mode\":\"DEFAULT\",\"rules\":[{\"tool\":\"execute\","
                                + "\"behavior\":\"DENY\"}]}");

        assertThat(first).isEqualTo(second);
        assertThat(first.json())
                .isEqualTo(
                        "{\"mode\":\"DEFAULT\",\"rules\":[{\"behavior\":\"DENY\","
                                + "\"tool\":\"execute\"}]}");
    }

    @Test
    void rejectsNonObjectSnapshots() {
        assertThatThrownBy(() -> PermissionSnapshotIntegrity.canonicalize("[\"execute\"]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Permission snapshot must be a JSON object");
    }
}
