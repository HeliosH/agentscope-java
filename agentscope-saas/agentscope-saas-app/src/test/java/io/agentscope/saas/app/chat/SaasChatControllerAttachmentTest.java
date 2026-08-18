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
package io.agentscope.saas.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.saas.app.chat.SaasChatController.ChatRequest;
import io.agentscope.saas.app.chat.SaasChatController.ChatRequest.AttachedFileInput;
import java.util.List;
import org.junit.jupiter.api.Test;

class SaasChatControllerAttachmentTest {

    @Test
    void agentMessageIncludesOnlySafeInputAttachments() {
        ChatRequest request =
                new ChatRequest(
                        null,
                        null,
                        "总结文件",
                        List.of(
                                new AttachedFileInput("inputs/report.pdf", "report.pdf", 12L),
                                new AttachedFileInput("MEMORY.md", "MEMORY.md", 8L),
                                new AttachedFileInput("inputs/../secret", "secret", 1L)),
                        null);

        String prompt = SaasChatController.agentMessage(request);

        assertThat(prompt)
                .contains("inputs/report.pdf", "User request:\n总结文件")
                .doesNotContain("MEMORY.md", "inputs/../secret");
    }
}
