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

import io.agentscope.saas.dal.mybatis.type.UuidTypeHandler;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MyBatis wiring for cross-tenant operational and pre-authentication queries.
 *
 * <p>Administrative mappers are isolated in a dedicated package and cannot accidentally use the
 * tenant-aware primary data source. Tenant-scoped mappers will use a separate session factory as
 * their bounded contexts migrate from JPA.
 */
@Configuration
@MapperScan(
        basePackages = "io.agentscope.saas.dal.mybatis.admin",
        sqlSessionTemplateRef = "adminSqlSessionTemplate")
public class MyBatisDataAccessConfig {

    @Bean
    public SqlSessionFactory adminSqlSessionFactory(
            @Qualifier("adminDataSource") DataSource adminDataSource) throws Exception {
        org.apache.ibatis.session.Configuration configuration =
                new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(adminDataSource);
        factory.setConfiguration(configuration);
        return factory.getObject();
    }

    @Bean
    public SqlSessionTemplate adminSqlSessionTemplate(
            @Qualifier("adminSqlSessionFactory") SqlSessionFactory sessionFactory) {
        return new SqlSessionTemplate(sessionFactory);
    }

    @Bean
    public TransactionOperations adminTransactionOperations(
            @Qualifier("adminDataSource") DataSource adminDataSource) {
        return new TransactionTemplate(new DataSourceTransactionManager(adminDataSource));
    }
}
