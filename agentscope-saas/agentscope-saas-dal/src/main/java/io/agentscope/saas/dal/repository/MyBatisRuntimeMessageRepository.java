/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.tenant.RuntimeMessageMapper;
import io.agentscope.saas.dal.mybatis.tenant.RuntimeStreamData;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisRuntimeMessageRepository implements RuntimeMessageRepository {
    private final RuntimeMessageMapper mapper;

    public MyBatisRuntimeMessageRepository(RuntimeMessageMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean lockSession(Scope scope) {
        return !mapper.lockSession(scope).isEmpty();
    }

    @Override
    public void ensureStream(UUID id, Scope scope) {
        mapper.ensureStream(id, scope);
    }

    @Override
    public Stream lockStream(Scope scope) {
        return domain(mapper.lockStream(scope));
    }

    @Override
    public Optional<Identity> findIdentity(UUID id, Scope scope, String messageId) {
        return mapper.findIdentity(id, scope, messageId).stream().findFirst();
    }

    @Override
    public List<Identity> findIdentities(UUID id, Scope scope, List<String> ids) {
        if (ids.isEmpty()) return List.of();
        if (ids.size() > 10000)
            throw new IllegalArgumentException("Runtime identity window too large");
        return mapper.findIdentities(id, scope, ids);
    }

    @Override
    public List<Message> findMessageMetadata(UUID id, Scope scope, List<String> ids) {
        if (ids.isEmpty()) return List.of();
        if (ids.size() > 500)
            throw new IllegalArgumentException("Checkpoint identity window too large");
        return mapper.findMessageMetadata(id, scope, ids);
    }

    @Override
    public Optional<Message> findOwnedSource(Scope scope, String messageId, String hash) {
        return mapper.findOwnedSource(scope, messageId, hash).stream().findFirst();
    }

    @Override
    public void insert(Scope scope, Message message) {
        if (mapper.insert(scope, message) != 1)
            throw new IllegalStateException("Runtime message insert failed");
    }

    @Override
    public void advance(Scope scope, UUID id, long seq, String messageId) {
        if (mapper.advance(scope, id, seq, messageId) != 1)
            throw new IllegalStateException("Runtime cursor update failed");
    }

    @Override
    public Optional<Stream> findStream(UUID org, UUID user, UUID agent, String label, String key) {
        var rows = mapper.findStream(org, user, agent, label, key);
        if (rows.size() > 1) throw new IllegalStateException("Ambiguous runtime session key");
        return rows.stream().findFirst().map(MyBatisRuntimeMessageRepository::domain);
    }

    @Override
    public Optional<Stream> findStream(Scope scope) {
        return mapper.findExactStream(scope).stream()
                .findFirst()
                .map(MyBatisRuntimeMessageRepository::domain);
    }

    @Override
    public List<Message> window(
            Scope scope, UUID id, Long after, Long before, int limit, long bytes) {
        if (limit < 1 || limit > 501 || bytes < 1)
            throw new IllegalArgumentException("Invalid archive window");
        return mapper.window(scope, id, after, before, limit, bytes);
    }

    @Override
    public boolean hasBeyond(Scope scope, UUID id, Long after, Long before) {
        return !mapper.hasBeyond(scope, id, after, before).isEmpty();
    }

    @Override
    public List<Hit> search(
            UUID org, UUID user, UUID agent, String label, String pattern, int limit) {
        return mapper.search(org, user, agent, label, pattern, Math.max(1, Math.min(limit, 100)));
    }

    @Override
    public List<Stream> list(UUID org, UUID user, UUID agent, String label, int limit) {
        return mapper.list(org, user, agent, label, Math.max(1, Math.min(limit, 100))).stream()
                .map(MyBatisRuntimeMessageRepository::domain)
                .toList();
    }

    @Override
    public void deleteSession(UUID org, UUID user, UUID session) {
        mapper.deleteSession(org, user, session);
    }

    private static Stream domain(RuntimeStreamData data) {
        if (data == null) throw new IllegalStateException("Runtime stream is not owned by caller");
        return new Stream(
                data.id(),
                new Scope(
                        data.orgId(),
                        data.userId(),
                        data.agentId(),
                        data.sessionId(),
                        data.agentLabel(),
                        data.sessionKey()),
                data.lastSeq(),
                data.lastMessageId());
    }
}
