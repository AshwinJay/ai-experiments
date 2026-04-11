package com.dbtespresso.testing;

import java.util.*;
import java.util.stream.*;

/**
 * Compiles generic test definitions into executable SQL.
 *
 * dbt's test contract: a test is a SELECT query. If it returns zero rows, the test
 * passes. If it returns one or more rows, each row represents a failure.
 *
 * <h2>Built-in tests</h2>
 * The four built-in tests (unique, not_null, accepted_values, relationships) are
 * compiled directly to SQL here. In dbt Core these are Jinja macros; we skip the
 * macro layer and generate SQL directly.
 *
 * <h2>dbt_expectations compatibility</h2>
 * dbt_expectations tests are Jinja macros that expand to SQL. For full compatibility,
 * you'd run them through the Jinja renderer. But the most common ones can be compiled
 * directly here as an optimization. Less common ones fall back to Jinja.
 *
 * <h2>Custom generic tests</h2>
 * Any test not recognized here must be resolved via the Jinja macro system from
 * the installed dbt packages (tests/generic/ or macros/ directories).
 */
public final class GenericTestCompiler {

    private GenericTestCompiler() {}

    /**
     * Compile a generic test to SQL.
     *
     * @param test       the test definition
     * @param tableName  the fully resolved table name (schema.table)
     * @return SQL that returns failing rows (empty result = pass)
     * @throws UnsupportedTestException if the test is not recognized
     */
    public static String compile(GenericTest test, String tableName) {
        if (test.isBuiltin()) {
            return compileBuiltin(test, tableName);
        }
        if (test.isExpectation()) {
            return compileExpectation(test, tableName);
        }
        throw new UnsupportedTestException(
                "Test '" + test.qualifiedName() + "' not recognized. "
                        + "It must be resolved via Jinja macro rendering.");
    }

    // ==================== Built-in tests ====================

    private static String compileBuiltin(GenericTest test, String table) {
        return switch (test.testName()) {
            case "not_null" -> compileNotNull(test, table);
            case "unique" -> compileUnique(test, table);
            case "accepted_values" -> compileAcceptedValues(test, table);
            case "relationships" -> compileRelationships(test, table);
            default -> throw new UnsupportedTestException("Unknown built-in: " + test.testName());
        };
    }

    static String compileNotNull(GenericTest test, String table) {
        String col = Objects.requireNonNull(test.columnName(), "not_null requires a column");
        String where = rowCondition(test);
        return "SELECT %s FROM %s WHERE %s IS NULL%s".formatted(col, table, col, where);
    }

    static String compileUnique(GenericTest test, String table) {
        String col = Objects.requireNonNull(test.columnName(), "unique requires a column");
        String where = rowCondition(test);
        return """
                SELECT %s, COUNT(*) AS __count
                FROM %s
                WHERE %s IS NOT NULL%s
                GROUP BY %s
                HAVING COUNT(*) > 1""".formatted(col, table, col, where, col);
    }

    static String compileAcceptedValues(GenericTest test, String table) {
        String col = Objects.requireNonNull(test.columnName());
        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) test.arguments().get("values");
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("accepted_values requires a 'values' argument");
        }
        String inList = values.stream()
                .map(v -> "'" + v.replace("'", "''") + "'")
                .collect(Collectors.joining(", "));
        String where = rowCondition(test);
        return "SELECT %s FROM %s WHERE %s NOT IN (%s)%s"
                .formatted(col, table, col, inList, where);
    }

    static String compileRelationships(GenericTest test, String table) {
        String col = Objects.requireNonNull(test.columnName());
        String toTable = (String) test.arguments().get("to");
        String field = (String) test.arguments().get("field");
        Objects.requireNonNull(toTable, "relationships requires 'to' argument");
        Objects.requireNonNull(field, "relationships requires 'field' argument");
        return """
                SELECT child.%s
                FROM %s AS child
                LEFT JOIN %s AS parent ON child.%s = parent.%s
                WHERE child.%s IS NOT NULL AND parent.%s IS NULL"""
                .formatted(col, table, toTable, col, field, col, field);
    }

    // ==================== dbt_expectations tests ====================
    // The most commonly used ones, compiled directly for performance.

    private static String compileExpectation(GenericTest test, String table) {
        return switch (test.testName()) {
            case "expect_column_values_to_not_be_null" ->
                    compileNotNull(test, table); // same as built-in not_null with row_condition

            case "expect_column_values_to_be_between" -> {
                String col = test.columnName();
                var args = test.arguments();
                String min = String.valueOf(args.get("min_value"));
                String max = String.valueOf(args.get("max_value"));
                String where = rowCondition(test);
                yield "SELECT %s FROM %s WHERE (%s < %s OR %s > %s)%s"
                        .formatted(col, table, col, min, col, max, where);
            }

            case "expect_column_values_to_be_in_set" -> {
                String col = test.columnName();
                @SuppressWarnings("unchecked")
                var values = (List<String>) test.arguments().get("value_set");
                String inList = values.stream()
                        .map(v -> "'" + v.replace("'", "''") + "'")
                        .collect(Collectors.joining(", "));
                String where = rowCondition(test);
                yield "SELECT %s FROM %s WHERE %s NOT IN (%s)%s"
                        .formatted(col, table, col, inList, where);
            }

            case "expect_column_values_to_match_regex" -> {
                String col = test.columnName();
                String regex = (String) test.arguments().get("regex");
                // Uses SQL standard LIKE or warehouse-specific REGEXP
                String where = rowCondition(test);
                yield "SELECT %s FROM %s WHERE NOT REGEXP_LIKE(%s, '%s')%s"
                        .formatted(col, table, col, regex.replace("'", "''"), where);
            }

            case "expect_column_values_to_be_unique" ->
                    compileUnique(test, table);

            case "expect_table_row_count_to_equal" -> {
                long expected = ((Number) test.arguments().get("value")).longValue();
                yield """
                        SELECT COUNT(*) AS __row_count FROM %s
                        HAVING COUNT(*) != %d""".formatted(table, expected);
            }

            case "expect_table_row_count_to_be_between" -> {
                long min = ((Number) test.arguments().get("min_value")).longValue();
                long max = ((Number) test.arguments().get("max_value")).longValue();
                yield """
                        SELECT COUNT(*) AS __row_count FROM %s
                        HAVING COUNT(*) < %d OR COUNT(*) > %d""".formatted(table, min, max);
            }

            case "expect_column_values_to_be_of_type" -> {
                // This requires warehouse-specific INFORMATION_SCHEMA queries
                // Placeholder — actual implementation depends on adapter
                String col = test.columnName();
                String type = (String) test.arguments().get("column_type");
                yield """
                        SELECT column_name, data_type
                        FROM information_schema.columns
                        WHERE table_name = '%s' AND column_name = '%s'
                        AND LOWER(data_type) != LOWER('%s')"""
                        .formatted(extractTableName(table), col, type);
            }

            case "expect_column_max_to_be_between" -> {
                String col = test.columnName();
                var args = test.arguments();
                String min = String.valueOf(args.get("min_value"));
                String max = String.valueOf(args.get("max_value"));
                yield """
                        SELECT MAX(%s) AS __max_value FROM %s
                        HAVING MAX(%s) < %s OR MAX(%s) > %s"""
                        .formatted(col, table, col, min, col, max);
            }

            default -> throw new UnsupportedTestException(
                    "dbt_expectations test '" + test.testName()
                            + "' not natively compiled. Requires Jinja macro resolution.");
        };
    }

    // ==================== Helpers ====================

    /**
     * Build an AND clause from row_condition if present.
     * dbt_expectations' row_condition parameter lets you scope tests to a subset of rows.
     */
    private static String rowCondition(GenericTest test) {
        Object rc = test.arguments().get("row_condition");
        if (rc instanceof String cond && !cond.isBlank()) {
            return " AND (" + cond + ")";
        }
        return "";
    }

    private static String extractTableName(String fqn) {
        String[] parts = fqn.split("\\.");
        return parts[parts.length - 1];
    }
}
