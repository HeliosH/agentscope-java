/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.admin.AuthIdentityMapper;
import io.agentscope.saas.domain.auth.AuthIdentityRepository;
import io.agentscope.saas.domain.auth.Organization;
import io.agentscope.saas.domain.auth.UserAccount;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** MyBatis adapter for the authentication domain persistence port. */
@Repository
public class MyBatisAuthIdentityRepository implements AuthIdentityRepository {

    private final AuthIdentityMapper mapper;

    public MyBatisAuthIdentityRepository(AuthIdentityMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<UserAccount> findUserByEmail(String email) {
        return mapper.findUserByEmail(email).stream()
                .findFirst()
                .map(
                        row ->
                                new UserAccount(
                                        row.id(),
                                        row.orgId(),
                                        row.email(),
                                        row.displayName(),
                                        row.passwordHash(),
                                        row.role(),
                                        row.tier()));
    }

    @Override
    public Optional<Organization> findOrganizationBySlug(String slug) {
        return mapper.findOrganizationBySlug(slug).stream()
                .findFirst()
                .map(row -> new Organization(row.id(), row.name(), row.slug(), row.status()));
    }

    @Override
    public UserAccount saveUser(UserAccount user) {
        int rows =
                mapper.insertUser(
                        user.id(),
                        user.orgId(),
                        user.email(),
                        user.displayName(),
                        user.passwordHash(),
                        user.role(),
                        user.tier());
        if (rows != 1) {
            throw new IllegalStateException("Expected one inserted user row, got " + rows);
        }
        return user;
    }
}
