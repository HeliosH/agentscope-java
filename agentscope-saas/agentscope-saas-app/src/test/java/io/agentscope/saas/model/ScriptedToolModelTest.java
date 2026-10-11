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
package io.agentscope.saas.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScriptedToolModelTest {

    private final ScriptedToolModel model = new ScriptedToolModel();

    @Test
    void newRequestDoesNotReuseAnEarlierToolResult() {
        var response =
                model.stream(
                                List.of(
                                        user("printf first"),
                                        result("first"),
                                        user("printf second")),
                                List.of(),
                                null)
                        .blockFirst();

        assertThat(response).isNotNull();
        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0)).isInstanceOf(ToolUseBlock.class);
        var call = (ToolUseBlock) response.getContent().get(0);
        assertThat(call.getName()).isEqualTo("execute");
        assertThat(call.getInput()).containsEntry("command", "printf second");
    }

    @Test
    void resultAfterLatestRequestCompletesCurrentTurn() {
        var response =
                model.stream(
                                List.of(
                                        user("printf first"),
                                        result("first"),
                                        user("printf second"),
                                        result("second")),
                                List.of(),
                                null)
                        .blockFirst();

        assertThat(response).isNotNull();
        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0)).isInstanceOf(TextBlock.class);
        assertThat(((TextBlock) response.getContent().get(0)).getText())
                .endsWith("second")
                .doesNotContain("first");
    }

    private static Msg user(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static Msg result(String text) {
        return Msg.builder()
                .role(MsgRole.TOOL)
                .content(
                        new ToolResultBlock(
                                "call", "execute", TextBlock.builder().text(text).build()))
                .build();
    }
}
