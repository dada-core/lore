package com.dadacore.lore.shared;

import java.util.List;

/**
 * A condition to insert in the {@code where} clause of a query, with its parameters in order: for
 * example the spaces a person can see, written once by the module that owns the rule.
 */
public record SqlCondition(String sql, List<Object> params) {

    public SqlCondition {
        params = List.copyOf(params);
    }

    public static SqlCondition always() {
        return new SqlCondition("true", List.of());
    }
}
