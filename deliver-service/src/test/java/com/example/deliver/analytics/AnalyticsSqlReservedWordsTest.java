package com.example.deliver.analytics;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Gate for QA D-1: a table whose name is a MySQL reserved word must be backticked in every
 * dataset's base SQL. {@code delivery} and {@code quality} both join {@code release}, so an
 * unquoted reference only showed up when those two datasets were actually run.
 */
class AnalyticsSqlReservedWordsTest {

    /** MySQL 8 reserved words that are also table names in this schema (release is the live one). */
    private static final Set<String> RESERVED = Set.of(
            "release", "order", "group", "key", "rank", "system", "user", "usage", "window");

    /** Matches {@code FROM/JOIN <name>} with the optional backtick captured as group 2. */
    private static final Pattern TABLE_REF = Pattern.compile(
            "(?i)\\b(from|join)\\s+(`?)([A-Za-z_][A-Za-z0-9_]*)\\2");

    @Test
    @DisplayName("every reserved table name is backticked in each dataset's base SQL")
    void reservedTableNamesAreQuoted() {
        List<String> offenders = new ArrayList<>();
        for (String dataset : AnalyticsDatasets.codes()) {
            String sql = AnalyticsSql.base(dataset);
            Matcher matcher = TABLE_REF.matcher(sql);
            while (matcher.find()) {
                String table = matcher.group(3);
                boolean quoted = !matcher.group(2).isEmpty();
                if (!quoted && RESERVED.contains(table.toLowerCase())) {
                    offenders.add(dataset + ": " + matcher.group(1) + " " + table);
                }
            }
        }
        assertTrue(offenders.isEmpty(), "reserved table names must be backticked: " + offenders);
    }
}
