package com.example.platform.common.scope;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A SQL fragment plus the named parameters it needs.
 *
 * <p>Kept as a value object so {@link ScopeSql} stays a pure function and can be unit-tested
 * without a database: the predicate text and its parameters are asserted directly.</p>
 */
public record SqlPredicate(String sql, Map<String, Object> params) {

    public SqlPredicate {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("scope predicate is required");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        if (params != null) {
            copy.putAll(params);
        }
        params = Map.copyOf(copy);
    }
}
