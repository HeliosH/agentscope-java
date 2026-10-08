/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.app.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.memory.mem0.Mem0Message;
import io.agentscope.saas.app.support.MyBatisRepositoryTestSupport;
import io.agentscope.saas.app.support.TestDatabaseMapper;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.dal.mybatis.admin.MemoryProjectionMapper;
import io.agentscope.saas.dal.mybatis.tenant.MemoryLedgerMapper;
import io.agentscope.saas.dal.repository.MyBatisMemoryEventRepository;
import io.agentscope.saas.dal.repository.MyBatisMemoryProjectionRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PgMemoryLedgerTest {
    private final String organization = UUID.randomUUID().toString();
    private final String user = UUID.randomUUID().toString();
    private final Map<String, Object> metadata =
            Map.of("source_message_ids", List.of("server-msg-1"));
    private PgMemoryLedger ledger;
    private MyBatisMemoryEventRepository events;
    private MyBatisMemoryProjectionRepository projections;

    @BeforeEach
    void setUp() {
        var ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:mem:memory-source-"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        MyBatisRepositoryTestSupport.mapper(ds, TestDatabaseMapper.class).createMemoryEvents();
        events =
                new MyBatisMemoryEventRepository(
                        MyBatisRepositoryTestSupport.mapper(ds, MemoryLedgerMapper.class));
        projections =
                new MyBatisMemoryProjectionRepository(
                        MyBatisRepositoryTestSupport.mapper(ds, MemoryProjectionMapper.class));
        ledger = new PgMemoryLedger(events, new ObjectMapper());
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    void duplicateSourceDoesNotResetActiveProjectionLease() {
        var ref = record("concise", tenant(organization));
        var now = OffsetDateTime.now();
        var pending = projections.findReplayable(10, now, now.minusSeconds(60)).get(0);
        var token = UUID.randomUUID();
        assertThat(
                        projections.claim(
                                pending, token, 3, now, now.plusSeconds(60), now.minusSeconds(60)))
                .isTrue();
        assertThat(record("concise", tenant(organization))).isEqualTo(ref);
        assertThat(events.findById(ref.id()).orElseThrow().getSyncStatus()).isEqualTo("syncing");
        assertThat(events.findById(ref.id()).orElseThrow().getSyncAttempts()).isEqualTo(1);
        assertThat(
                        projections.markSynced(
                                pending.orgId(), pending.id(), token, OffsetDateTime.now()))
                .isTrue();
        assertThat(record("concise", tenant(organization))).isEqualTo(ref);
        assertThat(events.findById(ref.id()).orElseThrow().getSyncStatus()).isEqualTo("synced");
    }

    @Test
    void duplicateIdentityWithChangedContentCannotOverwriteOriginalSource() {
        var ref = record("concise", tenant(organization));
        assertThatThrownBy(() -> record("verbose", tenant(organization)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MEMORY_SOURCE_ID_CONTENT_CONFLICT");
        assertThat(events.findById(ref.id()).orElseThrow().getContentJson())
                .contains("concise")
                .doesNotContain("verbose");
    }

    @Test
    void identityIncludesOrganizationAndRestoresCallerContext() {
        TenantContextHolder.setOrgId("caller-context");
        var first = record("concise", tenant(organization));
        var second = record("concise", tenant(UUID.randomUUID().toString()));
        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(TenantContextHolder.getOrgId()).isEqualTo("caller-context");
    }

    @Test
    void contextIsRestoredAfterContentConflict() {
        record("concise", tenant(organization));
        TenantContextHolder.setOrgId("caller-context");
        assertThatThrownBy(() -> record("verbose", tenant(organization)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(TenantContextHolder.getOrgId()).isEqualTo("caller-context");
    }

    private MemoryLedger.MemoryEventRef record(String value, TenantContext tenant) {
        return ledger.recordPending(
                        tenant,
                        "assistant",
                        "session-1",
                        List.of(Mem0Message.builder().role("user").content(value).build()),
                        metadata)
                .orElseThrow();
    }

    private TenantContext tenant(String orgId) {
        return new TenantContext(orgId, user, "member", "standard", 2, 0L);
    }
}
