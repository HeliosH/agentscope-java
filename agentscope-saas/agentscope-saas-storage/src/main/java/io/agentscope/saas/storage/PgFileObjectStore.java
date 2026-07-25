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

import io.agentscope.saas.dal.mybatis.tenant.BinaryBlobData;
import io.agentscope.saas.dal.mybatis.tenant.RelationalBlobStorageMapper;
import java.io.FileNotFoundException;
import java.util.Objects;
import java.util.UUID;

/** MyBatis-backed PostgreSQL/H2 BYTEA fallback object store for local development and tests. */
public final class PgFileObjectStore implements FileObjectStore {

    private final RelationalBlobStorageMapper mapper;
    private final String table;

    public PgFileObjectStore(RelationalBlobStorageMapper mapper, String table) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.table = SqlTableName.validate(table, "file_object_blobs");
    }

    @Override
    public String backend() {
        return "pg";
    }

    @Override
    public void put(FileObject object) throws Exception {
        Objects.requireNonNull(object, "object");
        byte[] content = object.content() != null ? object.content() : new byte[0];
        int updated =
                mapper.updateFileObject(
                        table,
                        object.objectKey(),
                        object.orgId(),
                        object.contentType(),
                        content.length,
                        object.sha256(),
                        content);
        if (updated == 0) {
            mapper.insertFileObject(
                    table,
                    object.objectKey(),
                    object.orgId(),
                    object.contentType(),
                    content.length,
                    object.sha256(),
                    content);
        }
    }

    @Override
    public byte[] get(UUID orgId, String objectKey) throws Exception {
        BinaryBlobData stored = mapper.findFileObject(table, orgId, objectKey);
        if (stored == null) {
            throw new FileNotFoundException("File object not found: " + objectKey);
        }
        return stored.getData();
    }

    @Override
    public void delete(UUID orgId, String objectKey) throws Exception {
        mapper.deleteFileObject(table, orgId, objectKey);
    }

    @Override
    public void healthCheck() throws Exception {
        mapper.healthCheck();
    }
}
