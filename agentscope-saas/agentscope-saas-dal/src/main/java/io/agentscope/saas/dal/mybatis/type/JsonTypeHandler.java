/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.dal.mybatis.type;

import java.nio.charset.StandardCharsets;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** Writes JSON values without turning them into JSON string literals on H2 or PostgreSQL. */
public class JsonTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(
            PreparedStatement statement, int index, String value, JdbcType jdbcType)
            throws SQLException {
        String database = statement.getConnection().getMetaData().getDatabaseProductName();
        if ("H2".equalsIgnoreCase(database)) {
            statement.setBytes(index, value.getBytes(StandardCharsets.UTF_8));
        } else if ("PostgreSQL".equalsIgnoreCase(database)) {
            statement.setObject(index, value, Types.OTHER);
        } else {
            statement.setString(index, value);
        }
    }

    @Override
    public String getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return resultSet.getString(columnName);
    }

    @Override
    public String getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return resultSet.getString(columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement statement, int columnIndex)
            throws SQLException {
        return statement.getString(columnIndex);
    }
}
