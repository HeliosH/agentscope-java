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
package io.agentscope.harness.agent.filesystem;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RemoteFilesystemBinaryUploadTest {

    @Test
    void invalidUtf8IsStoredAsBase64AndRoundTripsWithoutCorruption() {
        RemoteFilesystem filesystem =
                new RemoteFilesystem(new InMemoryStore(), List.of("test", "binary"));
        byte[] original = new byte[] {0x50, 0x4b, 0x03, 0x04, (byte) 0xff, 0x00, (byte) 0x80};

        var uploaded =
                filesystem.uploadFiles(
                        RuntimeContext.empty(),
                        List.of(Map.entry("/inputs/report.docx", original)));
        var downloaded =
                filesystem.downloadFiles(RuntimeContext.empty(), List.of("/inputs/report.docx"));

        assertTrue(uploaded.get(0).isSuccess());
        assertTrue(downloaded.get(0).isSuccess());
        assertArrayEquals(original, downloaded.get(0).content());
    }
}
