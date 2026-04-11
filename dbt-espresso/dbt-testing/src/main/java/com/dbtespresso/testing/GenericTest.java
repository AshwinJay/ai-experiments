package com.dbtespresso.testing;

import java.util.*;

/**
 * A generic data test definition, matching dbt's generic test framework.
 * These are the tests declared in schema.yml:
 *
 * <pre>
 * models:
 *   - name: orders
 *     columns:
 *       - name: order_id
 *         data_tests:
 *           - unique
 *           - not_null
 *           - dbt_expectations.expect_column_values_to_match_regex:
 *               regex: "^ORD-[0-9]+"
 * </pre>
 *
 * <h2>Built-in tests (dbt Core)</h2>
 * unique, not_null, accepted_values, relationships
 *
 * <h2>dbt_expectations tests</h2>
 * 62 tests like expect_column_values_to_not_be_null,
 * expect_column_values_to_be_between, expect_table_row_count_to_equal, etc.
 * Each is a Jinja macro that expands to a SELECT query returning failing rows.
 *
 * <h2>What dbt-espresso must do</h2>
 * <ol>
 *   <li>Parse the test name and arguments from YAML</li>
 *   <li>Resolve the test to a SQL template (built-in or from a package macro)</li>
 *   <li>Render the template with model/column/args context</li>
 *   <li>Execute the rendered SQL; if rows returned → test fails</li>
 * </ol>
 */
public record GenericTest(
        String testName,
        String packageName,
        String modelName,
        String columnName,
        Map<String, Object> arguments,
        TestSeverity severity,
        Map<String, Object> config
) {
    public enum TestSeverity { ERROR, WARN }

    public GenericTest {
        Objects.requireNonNull(testName);
        Objects.requireNonNull(modelName);
        arguments = arguments != null ? Map.copyOf(arguments) : Map.of();
        severity = severity != null ? severity : TestSeverity.ERROR;
        config = config != null ? Map.copyOf(config) : Map.of();
    }

    /** Fully qualified name: "dbt_expectations.expect_column_values_to_be_between" */
    public String qualifiedName() {
        return packageName != null ? packageName + "." + testName : testName;
    }

    /** Is this one of dbt's four built-in tests? */
    public boolean isBuiltin() {
        return packageName == null && Set.of("unique", "not_null",
                "accepted_values", "relationships").contains(testName);
    }

    /** Is this from the dbt_expectations package? */
    public boolean isExpectation() {
        return "dbt_expectations".equals(packageName);
    }
}
