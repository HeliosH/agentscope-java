/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.saas.dal.mybatis.tenant.RuntimeBodyMapper;
import io.agentscope.saas.dal.mybatis.tenant.TenantDirectoryMapper;
import io.agentscope.saas.dal.mybatis.type.UuidTypeHandler;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository.Scope;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class MyBatisDatabaseDialectTest {
    @Test
    void postgresQuotaLocksSerializeWritersWithoutBlockingForeignKeys() {
        assertQuotaSql("postgresql", "FOR NO KEY UPDATE");
    }

    @Test
    void h2QuotaLocksUseTheSupportedDialect() {
        assertQuotaSql("h2", "FOR UPDATE");
    }

    private static void assertQuotaSql(String databaseId, String expected) {
        var configuration = new Configuration();
        configuration.setDatabaseId(databaseId);
        configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
        configuration.addMapper(TenantDirectoryMapper.class);
        configuration.addMapper(RuntimeBodyMapper.class);
        var scope =
                new Scope(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "assistant",
                        "session");
        for (String id :
                new String[] {
                    TenantDirectoryMapper.class.getName() + ".lockOrg",
                    TenantDirectoryMapper.class.getName() + ".lockUser",
                    RuntimeBodyMapper.class.getName() + ".lockQuota"
                }) {
            var sql =
                    configuration
                            .getMappedStatement(id)
                            .getBoundSql(
                                    Map.of(
                                            "id",
                                            scope.orgId(),
                                            "orgId",
                                            scope.orgId(),
                                            "scope",
                                            scope))
                            .getSql();
            assertThat(sql.replaceAll("\\s+", " ")).contains(expected);
        }
    }
}
