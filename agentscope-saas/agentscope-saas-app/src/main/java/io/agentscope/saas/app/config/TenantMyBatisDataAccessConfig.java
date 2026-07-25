/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.app.config;

import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** MyBatis wiring for authenticated, tenant-scoped requests protected by PostgreSQL RLS. */
@Configuration
@MapperScan(
        basePackages = "io.agentscope.saas.dal.mybatis.tenant",
        sqlSessionTemplateRef = "tenantSqlSessionTemplate")
public class TenantMyBatisDataAccessConfig {

    @Bean
    public SqlSessionFactory tenantSqlSessionFactory(
            @Qualifier("dataSource") DataSource tenantDataSource) throws Exception {
        return MyBatisSessionFactorySupport.create(tenantDataSource);
    }

    @Bean
    public SqlSessionTemplate tenantSqlSessionTemplate(
            @Qualifier("tenantSqlSessionFactory") SqlSessionFactory sessionFactory) {
        return new SqlSessionTemplate(sessionFactory);
    }
}
