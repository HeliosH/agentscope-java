/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.storage;

import java.util.regex.Pattern;

/** Validates deploy-time table names before they are used in MyBatis identifier substitution. */
final class SqlTableName {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private SqlTableName() {}

    static String validate(String configured, String fallback) {
        String table = configured == null || configured.isBlank() ? fallback : configured.trim();
        if (!IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("Invalid SQL table name: " + table);
        }
        return table;
    }
}
