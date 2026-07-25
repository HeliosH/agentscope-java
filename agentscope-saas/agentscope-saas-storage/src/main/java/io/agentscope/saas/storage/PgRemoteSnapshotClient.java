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
package io.agentscope.saas.storage;

import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import io.agentscope.saas.dal.mybatis.tenant.BinaryBlobData;
import io.agentscope.saas.dal.mybatis.tenant.RelationalBlobStorageMapper;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MyBatis {@link RemoteSnapshotClient} that stores sandbox workspace tar archives as
 * {@code BYTEA} BLOBs. Works on PostgreSQL and H2 (PostgreSQL mode) via a portable two-step
 * upsert (update-then-insert) so that dev/local H2 verification doesn't require a separate
 * object store.
 *
 * <p>The backing table is created by Flyway, not by the storage adapter.
 */
public class PgRemoteSnapshotClient implements RemoteSnapshotClient {

    private static final Logger log = LoggerFactory.getLogger(PgRemoteSnapshotClient.class);

    private final RelationalBlobStorageMapper mapper;
    private final String table;

    public PgRemoteSnapshotClient(RelationalBlobStorageMapper mapper, String table) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.table = SqlTableName.validate(table, "agentscope_sandbox_snapshots");
    }

    @Override
    public void upload(String snapshotId, InputStream data) throws Exception {
        byte[] bytes = data.readAllBytes();
        log.info("[snapshot] upload snapshotId={} bytes={}", snapshotId, bytes.length);
        if (mapper.updateSnapshot(table, snapshotId, bytes) == 0) {
            mapper.insertSnapshot(table, snapshotId, bytes);
        }
    }

    @Override
    public InputStream download(String snapshotId) throws Exception {
        BinaryBlobData stored = mapper.findSnapshot(table, snapshotId);
        if (stored == null) {
            throw new FileNotFoundException("Snapshot not found: " + snapshotId);
        }
        return new ByteArrayInputStream(stored.getData());
    }

    @Override
    public boolean exists(String snapshotId) throws Exception {
        boolean found = mapper.countSnapshot(table, snapshotId) > 0;
        log.info("[snapshot] exists snapshotId={} -> {}", snapshotId, found);
        return found;
    }
}
