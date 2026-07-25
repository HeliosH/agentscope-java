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

/** Shared construction rules for the isolated tenant and administrative MyBatis sessions. */
final class MyBatisSessionFactorySupport {

    private MyBatisSessionFactorySupport() {}

    static SqlSessionFactory create(DataSource dataSource) throws Exception {
        org.apache.ibatis.session.Configuration configuration =
                new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        return factory.getObject();
    }
}
