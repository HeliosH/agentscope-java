/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.dal.mybatis.tenant.RelationalBlobStorageMapper;
import io.agentscope.saas.domain.model.AgentEntity;
import io.agentscope.saas.domain.model.AuditLogEntity;
import io.agentscope.saas.domain.model.ChatMessageEntity;
import io.agentscope.saas.domain.model.ChatSessionEntity;
import io.agentscope.saas.domain.model.FileAttachmentEntity;
import io.agentscope.saas.domain.model.FileEntity;
import io.agentscope.saas.domain.model.FileVersionEntity;
import io.agentscope.saas.domain.model.MarketplaceEntity;
import io.agentscope.saas.domain.model.MemoryEventEntity;
import io.agentscope.saas.domain.model.OrgEntity;
import io.agentscope.saas.domain.model.SandboxEntity;
import io.agentscope.saas.domain.model.TierPolicyEntity;
import io.agentscope.saas.domain.model.UsageRecordEntity;
import io.agentscope.saas.domain.model.UserEntity;
import io.agentscope.saas.domain.repository.AgentRepository;
import io.agentscope.saas.domain.repository.AuditLogRepository;
import io.agentscope.saas.domain.repository.ChatMessageRepository;
import io.agentscope.saas.domain.repository.ChatSessionRepository;
import io.agentscope.saas.domain.repository.FileAttachmentRepository;
import io.agentscope.saas.domain.repository.FileRepository;
import io.agentscope.saas.domain.repository.FileVersionRepository;
import io.agentscope.saas.domain.repository.MarketplaceRepository;
import io.agentscope.saas.domain.repository.MemoryEventRepository;
import io.agentscope.saas.domain.repository.OrgRepository;
import io.agentscope.saas.domain.repository.SandboxRepository;
import io.agentscope.saas.domain.repository.TierPolicyRepository;
import io.agentscope.saas.domain.repository.UsageRecordRepository;
import io.agentscope.saas.domain.repository.UserRepository;
import io.agentscope.saas.storage.FileObject;
import io.agentscope.saas.storage.PgFileObjectStore;
import io.agentscope.saas.storage.PgRemoteSnapshotClient;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

/** Database-level contract test for every domain repository migrated from Spring Data JPA. */
@SpringBootTest
@ActiveProfiles("local")
class DomainRepositoryMyBatisIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired OrgRepository orgs;
    @Autowired UserRepository users;
    @Autowired TierPolicyRepository tiers;
    @Autowired AgentRepository agents;
    @Autowired ChatSessionRepository sessions;
    @Autowired ChatMessageRepository messages;
    @Autowired FileRepository files;
    @Autowired FileVersionRepository versions;
    @Autowired FileAttachmentRepository attachments;
    @Autowired MemoryEventRepository memoryEvents;
    @Autowired MarketplaceRepository marketplaces;
    @Autowired AuditLogRepository auditLogs;
    @Autowired UsageRecordRepository usageRecords;
    @Autowired SandboxRepository sandboxes;
    @Autowired RelationalBlobStorageMapper blobStorageMapper;

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    @Transactional
    void allMigratedDomainRepositoriesRoundTripThroughMyBatis() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        restartTransactionForTenant(orgId);

        TierPolicyEntity tier = tier("integration-" + orgId.toString().substring(0, 8));
        tiers.save(tier);
        assertThat(tiers.existsById(tier.getTier())).isTrue();
        assertThat(tiers.findById(tier.getTier()))
                .get()
                .extracting(TierPolicyEntity::getMaxAgents)
                .isEqualTo(7);

        OrgEntity org = new OrgEntity();
        org.setId(orgId);
        org.setName("MyBatis integration");
        org.setSlug("mybatis-" + orgId);
        org.setStatus("active");
        org.setSettings("{\"region\":\"internal\"}");
        orgs.save(org);
        assertThat(orgs.findBySlug(org.getSlug()))
                .get()
                .extracting(OrgEntity::getId)
                .isEqualTo(orgId);
        assertThat(orgs.lockTenantOrg(orgId)).isPresent();

        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setOrgId(orgId);
        user.setEmail("mybatis-" + userId + "@example.test");
        user.setIdpSubject("subject-" + userId);
        user.setDisplayName("Repository User");
        user.setPasswordHash("hash");
        user.setRole("admin");
        user.setTier(tier.getTier());
        users.save(user);
        assertThat(users.findByEmail(user.getEmail()))
                .get()
                .extracting(UserEntity::getId)
                .isEqualTo(userId);
        assertThat(users.findByIdpSubject(user.getIdpSubject())).isPresent();
        assertThat(users.findByOrgIdOrderByCreatedAtDesc(orgId, 10))
                .extracting(UserEntity::getId)
                .contains(userId);
        assertThat(users.countByOrgIdAndRole(orgId, "admin")).isEqualTo(1);
        assertThat(users.lockTenantUser(orgId, userId)).isPresent();

        AgentEntity agent = agent(orgId, userId, now);
        agents.save(agent);
        assertThat(agents.findByIdAndOrgId(agent.getId(), orgId)).isPresent();
        assertThat(agents.lockOwnedAgent(agent.getId(), orgId, userId)).isPresent();
        assertThat(agents.findByOrgIdAndUserIdAndName(orgId, userId, agent.getName())).isPresent();
        agent.setDescription("updated");
        agent.setUpdatedAt(now.plusSeconds(1));
        agents.save(agent);
        assertThat(agents.findByIdAndOrgId(agent.getId(), orgId))
                .get()
                .extracting(AgentEntity::getDescription)
                .isEqualTo("updated");

        ChatSessionEntity session = session(orgId, userId, agent.getId(), now);
        sessions.save(session);
        assertThat(sessions.lockById(session.getId())).isPresent();
        assertThat(
                        sessions.findByIdAndOrgIdAndUserIdAndAgentId(
                                session.getId(), orgId, userId, agent.getId()))
                .isPresent();

        ChatMessageEntity first = message(orgId, userId, agent.getId(), session.getId(), 1L);
        ChatMessageEntity second = message(orgId, userId, agent.getId(), session.getId(), 2L);
        messages.save(first);
        messages.save(second);
        assertThat(messages.countBySessionId(session.getId())).isEqualTo(2);
        assertThat(messages.maxSeq(session.getId())).isEqualTo(2);
        assertThat(messages.pageAfterSeq(session.getId(), 1L, 10))
                .extracting(ChatMessageEntity::getSeq)
                .containsExactly(2L);
        assertThat(messages.pageBeforeSeq(session.getId(), null, 1))
                .extracting(ChatMessageEntity::getSeq)
                .containsExactly(2L);

        FileEntity file = file(orgId, userId, agent.getId(), session.getId(), now);
        files.save(file);
        FileVersionEntity version =
                version(file.getId(), orgId, userId, agent.getId(), session.getId());
        versions.save(version);
        file.setCurrentVersionId(version.getId());
        file.setUpdatedAt(now.plusSeconds(2));
        files.save(file);
        assertThat(files.lockByOrgUserPath(orgId, userId, file.getLogicalPath())).isPresent();
        assertThat(versions.findFirstByFileIdOrderByVersionNoDesc(file.getId())).isPresent();
        assertThat(versions.findAllById(List.of(version.getId())))
                .extracting(FileVersionEntity::getId)
                .containsExactly(version.getId());
        assertThat(versions.currentUsageByUser(orgId, userId)).isEqualTo(42);
        assertThat(versions.currentUsageByOrg(orgId)).isEqualTo(42);

        FileAttachmentEntity attachment =
                attachment(
                        orgId,
                        userId,
                        agent.getId(),
                        session.getId(),
                        first.getId(),
                        file.getId(),
                        version.getId());
        attachments.save(attachment);
        assertThat(
                        attachments.findByOrgIdAndUserIdAndSessionIdOrderByCreatedAtDesc(
                                orgId, userId, session.getId()))
                .extracting(FileAttachmentEntity::getId)
                .contains(attachment.getId());
        assertThat(
                        attachments.findByOrgIdAndUserIdAndMessageIdOrderByCreatedAtDesc(
                                orgId, userId, first.getId()))
                .extracting(FileAttachmentEntity::getId)
                .contains(attachment.getId());

        MemoryEventEntity memory = memory(orgId, userId, agent.getId(), session.getId(), now);
        memoryEvents.save(memory);
        assertThat(
                        JSON.readTree(
                                memoryEvents
                                        .findById(memory.getId())
                                        .orElseThrow()
                                        .getContentJson()))
                .isEqualTo(JSON.readTree("{\"fact\":\"internal\"}"));
        memory.setSyncStatus("synced");
        memory.setSyncAttempts(1);
        memory.setUpdatedAt(now.plusSeconds(3));
        memoryEvents.save(memory);
        assertThat(
                        memoryEvents.findAdminEvents(
                                orgId, userId, session.getId().toString(), "synced", 5))
                .extracting(MemoryEventEntity::getId)
                .containsExactly(memory.getId());

        MarketplaceEntity marketplace = marketplace(orgId, now);
        marketplaces.save(marketplace);
        assertThat(
                        JSON.readTree(
                                marketplaces
                                        .findByOrgIdAndMarketplaceId(
                                                orgId, marketplace.getMarketplaceId())
                                        .orElseThrow()
                                        .getProperties()))
                .isEqualTo(JSON.readTree("{\"url\":\"https://internal.example\"}"));
        marketplace.setType("mcp");
        marketplace.setUpdatedAt(now.plusSeconds(4));
        marketplaces.save(marketplace);
        assertThat(marketplaces.findByOrgIdOrderByIdAsc(orgId))
                .extracting(MarketplaceEntity::getType)
                .contains("mcp");

        AuditLogEntity audit = new AuditLogEntity();
        audit.setOrgId(orgId);
        audit.setActor(userId);
        audit.setAction("repository.test");
        audit.setResource("agent:" + agent.getId());
        audit.setDetail("{\"result\":\"ok\"}");
        auditLogs.save(audit);
        assertThat(audit.getId()).isNotNull();
        assertThat(auditLogs.findAdminAuditLogs(orgId, userId, "repository.test", "agent:", 5))
                .extracting(AuditLogEntity::getId)
                .contains(audit.getId());

        UsageRecordEntity usage = new UsageRecordEntity();
        usage.setOrgId(orgId);
        usage.setUserId(userId);
        usage.setMetric("tokens");
        usage.setValue(123L);
        usage.setModel("internal-model");
        usageRecords.save(usage);
        assertThat(usage.getId()).isNotNull();
        assertThat(usageRecords.countByOrgId(orgId)).isEqualTo(1);
        assertThat(usageRecords.aggregateUsage(orgId, userId, "tokens", null, null))
                .singleElement()
                .satisfies(
                        aggregate -> {
                            assertThat(aggregate.metric()).isEqualTo("tokens");
                            assertThat(aggregate.model()).isEqualTo("internal-model");
                            assertThat(aggregate.totalValue()).isEqualTo(123);
                        });

        SandboxEntity sandbox = sandbox(orgId, userId, agent.getId(), session.getId(), now);
        sandboxes.save(sandbox);
        assertThat(sandboxes.countByOrgIdAndUserIdAndStatus(orgId, userId, "active")).isEqualTo(1);
        assertThat(sandboxes.findById(sandbox.getId())).isPresent();

        assertThat(
                        marketplaces.deleteByOrgIdAndMarketplaceId(
                                orgId, marketplace.getMarketplaceId()))
                .isEqualTo(1);
        assertThat(marketplaces.findByOrgIdAndMarketplaceId(orgId, marketplace.getMarketplaceId()))
                .isEmpty();
    }

    @Test
    @Transactional
    void relationalBinaryFallbacksRoundTripThroughMyBatis() throws Exception {
        UUID orgId = UUID.randomUUID();
        restartTransactionForTenant(orgId);

        OrgEntity org = new OrgEntity();
        org.setId(orgId);
        org.setName("Blob integration");
        org.setSlug("blob-" + orgId);
        org.setStatus("active");
        org.setSettings("{}");
        orgs.save(org);

        byte[] first = "first".getBytes();
        byte[] second = "second".getBytes();
        String objectKey = "files/" + UUID.randomUUID();
        PgFileObjectStore objectStore =
                new PgFileObjectStore(blobStorageMapper, "file_object_blobs");
        objectStore.put(new FileObject(orgId, objectKey, first, "text/plain", "sha-first"));
        assertThat(objectStore.get(orgId, objectKey)).isEqualTo(first);
        objectStore.put(new FileObject(orgId, objectKey, second, "text/plain", "sha-second"));
        assertThat(objectStore.get(orgId, objectKey)).isEqualTo(second);
        objectStore.healthCheck();
        objectStore.delete(orgId, objectKey);
        assertThatThrownBy(() -> objectStore.get(orgId, objectKey))
                .isInstanceOf(FileNotFoundException.class);

        String snapshotId = "snapshot-" + UUID.randomUUID();
        PgRemoteSnapshotClient snapshots =
                new PgRemoteSnapshotClient(blobStorageMapper, "agentscope_sandbox_snapshots");
        snapshots.upload(snapshotId, new ByteArrayInputStream(first));
        assertThat(snapshots.exists(snapshotId)).isTrue();
        assertThat(snapshots.download(snapshotId).readAllBytes()).isEqualTo(first);
        snapshots.upload(snapshotId, new ByteArrayInputStream(second));
        assertThat(snapshots.download(snapshotId).readAllBytes()).isEqualTo(second);

        assertThatThrownBy(() -> new PgFileObjectStore(blobStorageMapper, "invalid;table"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void restartTransactionForTenant(UUID orgId) {
        TestTransaction.end();
        TenantContextHolder.setOrgId(orgId.toString());
        TestTransaction.start();
    }

    private static TierPolicyEntity tier(String name) {
        TierPolicyEntity tier = new TierPolicyEntity();
        tier.setTier(name);
        tier.setMaxAgents(7);
        tier.setMaxSandboxes(3);
        tier.setMonthlyTokenQuota(100_000L);
        tier.setStorageGb(5);
        tier.setIdleTtlSeconds(600);
        return tier;
    }

    private static AgentEntity agent(UUID orgId, UUID userId, OffsetDateTime now) {
        AgentEntity agent = new AgentEntity();
        agent.setId(UUID.randomUUID());
        agent.setOrgId(orgId);
        agent.setUserId(userId);
        agent.setName("repository-agent");
        agent.setVisibility("private");
        agent.setStatus("active");
        agent.setDescription("initial");
        agent.setSysPrompt("help");
        agent.setMaxIters(5);
        agent.setTools("[]");
        agent.setWorkspacePath("/workspace");
        agent.setBuiltin(false);
        agent.setUpdatedAt(now);
        return agent;
    }

    private static ChatSessionEntity session(
            UUID orgId, UUID userId, UUID agentId, OffsetDateTime now) {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(UUID.randomUUID());
        session.setOrgId(orgId);
        session.setUserId(userId);
        session.setAgentId(agentId);
        session.setTitle("repository session");
        session.setMessageCount(0);
        session.setSource("user");
        session.setLabel("integration");
        session.setUnread(false);
        session.setLastMessage("hello");
        session.setUpdatedAt(now);
        return session;
    }

    private static ChatMessageEntity message(
            UUID orgId, UUID userId, UUID agentId, UUID sessionId, long seq) {
        ChatMessageEntity message = new ChatMessageEntity();
        message.setId(UUID.randomUUID());
        message.setOrgId(orgId);
        message.setUserId(userId);
        message.setAgentId(agentId);
        message.setSessionId(sessionId);
        message.setSeq(seq);
        message.setRole("user");
        message.setContentJson("[{\"type\":\"text\",\"text\":\"message " + seq + "\"}]");
        return message;
    }

    private static FileEntity file(
            UUID orgId, UUID userId, UUID agentId, UUID sessionId, OffsetDateTime now) {
        FileEntity file = new FileEntity();
        file.setId(UUID.randomUUID());
        file.setOrgId(orgId);
        file.setUserId(userId);
        file.setAgentId(agentId);
        file.setSessionId(sessionId);
        file.setLogicalPath("reports/integration.txt");
        file.setSource("assistant");
        file.setStatus("active");
        file.setUpdatedAt(now);
        return file;
    }

    private static FileVersionEntity version(
            UUID fileId, UUID orgId, UUID userId, UUID agentId, UUID sessionId) {
        FileVersionEntity version = new FileVersionEntity();
        version.setId(UUID.randomUUID());
        version.setFileId(fileId);
        version.setOrgId(orgId);
        version.setUserId(userId);
        version.setAgentId(agentId);
        version.setSessionId(sessionId);
        version.setVersionNo(1L);
        version.setObjectKey("objects/" + version.getId());
        version.setStorageBackend("postgres");
        version.setContentType("text/plain");
        version.setSizeBytes(42L);
        version.setSha256("0".repeat(64));
        version.setSource("assistant");
        version.setMetadata("{\"kind\":\"report\"}");
        return version;
    }

    private static FileAttachmentEntity attachment(
            UUID orgId,
            UUID userId,
            UUID agentId,
            UUID sessionId,
            UUID messageId,
            UUID fileId,
            UUID versionId) {
        FileAttachmentEntity attachment = new FileAttachmentEntity();
        attachment.setId(UUID.randomUUID());
        attachment.setOrgId(orgId);
        attachment.setUserId(userId);
        attachment.setAgentId(agentId);
        attachment.setSessionId(sessionId);
        attachment.setMessageId(messageId);
        attachment.setFileId(fileId);
        attachment.setFileVersionId(versionId);
        attachment.setKind("output");
        attachment.setMetadata("{\"visible\":true}");
        return attachment;
    }

    private static MemoryEventEntity memory(
            UUID orgId, UUID userId, UUID agentId, UUID sessionId, OffsetDateTime now) {
        MemoryEventEntity event = new MemoryEventEntity();
        event.setId(UUID.randomUUID());
        event.setOrgId(orgId);
        event.setUserId(userId);
        event.setAgentId(agentId.toString());
        event.setSessionId(sessionId.toString());
        event.setSource("conversation");
        event.setEventType("fact");
        event.setContentJson("{\"fact\":\"internal\"}");
        event.setMetadataJson("{\"scope\":\"user\"}");
        event.setSyncStatus("pending");
        event.setSyncAttempts(0);
        event.setUpdatedAt(now);
        return event;
    }

    private static MarketplaceEntity marketplace(UUID orgId, OffsetDateTime now) {
        MarketplaceEntity marketplace = new MarketplaceEntity();
        marketplace.setId(UUID.randomUUID());
        marketplace.setOrgId(orgId);
        marketplace.setMarketplaceId("internal-skills");
        marketplace.setType("skills");
        marketplace.setProperties("{\"url\":\"https://internal.example\"}");
        marketplace.setUpdatedAt(now);
        return marketplace;
    }

    private static SandboxEntity sandbox(
            UUID orgId, UUID userId, UUID agentId, UUID sessionId, OffsetDateTime now) {
        SandboxEntity sandbox = new SandboxEntity();
        sandbox.setId(UUID.randomUUID());
        sandbox.setOrgId(orgId);
        sandbox.setUserId(userId);
        sandbox.setAgentId(agentId);
        sandbox.setSessionId(sessionId.toString());
        sandbox.setSandboxType("opensandbox");
        sandbox.setExternalId("sandbox-" + sandbox.getId());
        sandbox.setStatus("active");
        sandbox.setLastUsedAt(now);
        sandbox.setExpiresAt(now.plusHours(1));
        sandbox.setBackendReleaseStatus("pending");
        sandbox.setBackendReleaseAttempts(0);
        return sandbox;
    }
}
