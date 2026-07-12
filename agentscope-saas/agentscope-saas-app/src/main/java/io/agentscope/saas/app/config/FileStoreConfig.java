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

import io.agentscope.saas.app.workspace.ClamAvFileContentScanner;
import io.agentscope.saas.app.workspace.FileContentScanner;
import io.agentscope.saas.storage.FileObjectStore;
import io.agentscope.saas.storage.MinioFileObjectStoreFactory;
import io.agentscope.saas.storage.PgFileObjectStore;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires durable workspace file object storage. */
@Configuration
@ConditionalOnProperty(prefix = "saas.file-store", name = "enabled", havingValue = "true")
public class FileStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(FileStoreConfig.class);

    @Bean
    public FileObjectStore fileObjectStore(
            SaasProperties properties, ObjectProvider<DataSource> dataSourceProvider) {
        SaasProperties.FileStore cfg = properties.getFileStore();
        String backend = cfg.getBackend() == null ? "pg" : cfg.getBackend().trim().toLowerCase();
        if ("minio".equals(backend)) {
            SaasProperties.FileStore.Minio m = cfg.getMinio();
            log.info(
                    "Using MinIO-backed file object store bucket={} endpoint={}",
                    m.getBucket(),
                    m.getEndpoint());
            return MinioFileObjectStoreFactory.create(
                    m.getEndpoint(),
                    m.getAccessKey(),
                    m.getSecretKey(),
                    m.getRegion(),
                    m.getBucket());
        }
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new IllegalStateException("file-store backend=pg requires a DataSource");
        }
        log.info("Using PostgreSQL/H2-backed file object store table={}", cfg.getTable());
        return new PgFileObjectStore(dataSource, cfg.getTable());
    }

    @Bean
    public FileContentScanner fileContentScanner(SaasProperties properties) {
        SaasProperties.FileStore.Antivirus cfg = properties.getFileStore().getAntivirus();
        if (!cfg.isEnabled()) {
            return FileContentScanner.noop();
        }
        log.info("ClamAV upload scanning enabled at {}:{}", cfg.getHost(), cfg.getPort());
        return new ClamAvFileContentScanner(
                cfg.getHost(), cfg.getPort(), cfg.getTimeoutSeconds(), cfg.isFailClosed());
    }
}
