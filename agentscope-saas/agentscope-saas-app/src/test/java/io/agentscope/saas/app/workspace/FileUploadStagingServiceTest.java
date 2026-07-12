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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.saas.app.config.SaasProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

class FileUploadStagingServiceTest {

    @Test
    void stagesBoundedUploadScansItAndDeletesTemporaryFileOnClose() throws Exception {
        SaasProperties properties = new SaasProperties();
        properties.getFileStore().setMaxFileBytes(32);
        AtomicBoolean scanned = new AtomicBoolean();
        FileUploadStagingService service =
                new FileUploadStagingService(properties, path -> scanned.set(Files.exists(path)));

        FileUploadStagingService.StagedUpload staged =
                service.stage(file("report.txt", "hello")).block();

        assertThat(staged).isNotNull();
        assertThat(staged.readAllBytes()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        assertThat(staged.sizeBytes()).isEqualTo(5L);
        assertThat(scanned).isTrue();
        assertThat(staged.path()).exists();
        staged.close();
        assertThat(staged.path()).doesNotExist();
    }

    @Test
    void rejectsUploadAsSoonAsStreamExceedsLimit() {
        SaasProperties properties = new SaasProperties();
        properties.getFileStore().setMaxFileBytes(4);
        FileUploadStagingService service =
                new FileUploadStagingService(properties, FileContentScanner.noop());

        assertThatThrownBy(() -> service.stage(file("report.txt", "hello")).block())
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsExecutableMagicEvenWhenExtensionIsInnocent() {
        assertThat(FileUploadStagingService.isExecutableHeader(new byte[] {'M', 'Z', 0, 0}))
                .isTrue();
        assertThat(FileUploadStagingService.isExecutableHeader(new byte[] {0x7f, 'E', 'L', 'F'}))
                .isTrue();
        assertThat(FileUploadStagingService.isExecutableHeader(new byte[] {'t', 'e', 'x', 't'}))
                .isFalse();
    }

    private static FilePart file(String filename, String content) {
        FilePart file = mock(FilePart.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        when(file.filename()).thenReturn(filename);
        when(file.headers()).thenReturn(headers);
        when(file.content())
                .thenReturn(
                        Flux.just(
                                DefaultDataBufferFactory.sharedInstance.wrap(
                                        content.getBytes(StandardCharsets.UTF_8))));
        return file;
    }
}
