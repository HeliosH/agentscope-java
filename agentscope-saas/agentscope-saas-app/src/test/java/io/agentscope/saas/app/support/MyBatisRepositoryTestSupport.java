/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.app.support;

import io.agentscope.saas.dal.mybatis.type.UuidTypeHandler;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;

/** Creates a real MyBatis mapper over a test data source. */
public final class MyBatisRepositoryTestSupport {

    private MyBatisRepositoryTestSupport() {}

    public static <T> T mapper(DataSource dataSource, Class<T> mapperType) {
        try {
            org.apache.ibatis.session.Configuration configuration =
                    new org.apache.ibatis.session.Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setArgNameBasedConstructorAutoMapping(true);
            configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
            configuration.addMapper(mapperType);
            SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            factoryBean.setConfiguration(configuration);
            SqlSessionFactory factory = factoryBean.getObject();
            if (factory == null) {
                throw new IllegalStateException("MyBatis test session factory was not created");
            }
            return new SqlSessionTemplate(factory).getMapper(mapperType);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create MyBatis test mapper", e);
        }
    }
}
