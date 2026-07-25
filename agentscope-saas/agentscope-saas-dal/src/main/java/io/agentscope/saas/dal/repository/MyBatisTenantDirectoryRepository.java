/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.tenant.TenantDirectoryMapper;
import io.agentscope.saas.domain.model.OrgEntity;
import io.agentscope.saas.domain.model.TierPolicyEntity;
import io.agentscope.saas.domain.model.UserEntity;
import io.agentscope.saas.domain.repository.OrgRepository;
import io.agentscope.saas.domain.repository.TierPolicyRepository;
import io.agentscope.saas.domain.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** MyBatis adapter for tenant directory domain ports. */
@Repository
public class MyBatisTenantDirectoryRepository
        implements OrgRepository, UserRepository, TierPolicyRepository {

    private final TenantDirectoryMapper mapper;

    public MyBatisTenantDirectoryRepository(TenantDirectoryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<OrgEntity> findBySlug(String slug) {
        return first(mapper.findOrgBySlug(slug));
    }

    @Override
    public Optional<OrgEntity> findById(UUID id) {
        return first(mapper.findOrg(id));
    }

    @Override
    public Optional<OrgEntity> lockTenantOrg(UUID orgId) {
        return first(mapper.lockOrg(orgId));
    }

    @Override
    public OrgEntity save(OrgEntity org) {
        if (mapper.updateOrg(org) == 0) {
            requireOne(mapper.insertOrg(org), "insert Org " + org.getId());
        }
        return org;
    }

    @Override
    public Optional<UserEntity> findByEmail(String email) {
        return first(mapper.findUserByEmail(email));
    }

    @Override
    public Optional<UserEntity> findByIdpSubject(String idpSubject) {
        return first(mapper.findUserBySubject(idpSubject));
    }

    @Override
    public List<UserEntity> findByOrgIdOrderByCreatedAtDesc(UUID orgId, int limit) {
        return mapper.findUsers(orgId, limit);
    }

    @Override
    public Optional<UserEntity> findByOrgIdAndId(UUID orgId, UUID id) {
        return first(mapper.findUser(orgId, id));
    }

    @Override
    public long countByOrgIdAndRole(UUID orgId, String role) {
        return mapper.countUsersByRole(orgId, role);
    }

    @Override
    public Optional<UserEntity> lockTenantUser(UUID orgId, UUID userId) {
        return first(mapper.lockUser(orgId, userId));
    }

    @Override
    public UserEntity save(UserEntity user) {
        if (mapper.updateUser(user) == 0) {
            requireOne(mapper.insertUser(user), "insert User " + user.getId());
        }
        return user;
    }

    @Override
    public Optional<TierPolicyEntity> findById(String tier) {
        return first(mapper.findTier(tier));
    }

    @Override
    public boolean existsById(String tier) {
        return !mapper.findTier(tier).isEmpty();
    }

    @Override
    public List<TierPolicyEntity> findAll() {
        return mapper.findTiers();
    }

    @Override
    public TierPolicyEntity save(TierPolicyEntity policy) {
        if (mapper.updateTier(policy) == 0) {
            requireOne(mapper.insertTier(policy), "insert Tier " + policy.getTier());
        }
        return policy;
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static void requireOne(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException(operation + " affected " + rows + " rows");
        }
    }
}
