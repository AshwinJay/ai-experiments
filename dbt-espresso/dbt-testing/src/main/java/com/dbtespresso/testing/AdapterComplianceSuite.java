package com.dbtespresso.testing;

import java.util.*;

/**
 * Compliance test suite for warehouse adapters. Tests that an adapter
 * correctly implements the {@link AdapterContract} interface.
 *
 * This is the dbt_adapters equivalent: a harness that adapter authors
 * run to verify their implementation is correct before shipping.
 *
 * <h2>Usage</h2>
 * <pre>
 * // In your adapter's test suite:
 * class SnowflakeAdapterComplianceTest {
 *     {@literal @}Test void compliance() {
 *         var adapter = new SnowflakeAdapter(testCredentials);
 *         var results = AdapterComplianceSuite.runAll(adapter, "test_schema");
 *         assertThat(results.failures()).isEmpty();
 *     }
 * }
 * </pre>
 */
public final class AdapterComplianceSuite {

    public record TestResult(String testName, boolean passed, String message) {}
    public record SuiteResult(List<TestResult> results) {
        public List<TestResult> failures() {
            return results.stream().filter(r -> !r.passed()).toList();
        }
        public boolean allPassed() { return failures().isEmpty(); }
    }

    private AdapterComplianceSuite() {}

    /**
     * Run all compliance tests against an adapter.
     *
     * @param adapter the adapter to test
     * @param testSchema a schema the tests can use (will create/drop objects in it)
     * @return suite results
     */
    public static SuiteResult runAll(AdapterContract adapter, String testSchema) {
        List<TestResult> results = new ArrayList<>();

        results.add(testConnection(adapter));
        results.add(testAdapterType(adapter));
        results.add(testCreateAndDropTable(adapter, testSchema));
        results.add(testCreateAndDropView(adapter, testSchema));
        results.add(testGetColumns(adapter, testSchema));
        results.add(testListRelations(adapter, testSchema));
        results.add(testRelationExists(adapter, testSchema));
        results.add(testExecuteSelect(adapter));
        results.add(testRenameRelation(adapter, testSchema));
        results.add(testReplaceTable(adapter, testSchema));

        return new SuiteResult(results);
    }

    static TestResult testConnection(AdapterContract adapter) {
        try {
            adapter.open();
            boolean connected = adapter.isConnected();
            adapter.close();
            return new TestResult("connection", connected,
                    connected ? "OK" : "isConnected() returned false after open()");
        } catch (Exception e) {
            return new TestResult("connection", false, "Exception: " + e.getMessage());
        }
    }

    static TestResult testAdapterType(AdapterContract adapter) {
        try {
            String type = adapter.adapterType();
            boolean valid = type != null && !type.isBlank();
            return new TestResult("adapter_type", valid,
                    valid ? "type=" + type : "adapterType() returned null/blank");
        } catch (Exception e) {
            return new TestResult("adapter_type", false, e.getMessage());
        }
    }

    static TestResult testCreateAndDropTable(AdapterContract adapter, String schema) {
        String name = "__espresso_compliance_table";
        try {
            adapter.open();
            adapter.createTableAs(schema, name, "SELECT 1 AS id, 'test' AS val", false);
            boolean exists = adapter.relationExists(schema, name);
            adapter.dropRelation(schema, name, AdapterContract.RelationType.TABLE);
            boolean gone = !adapter.relationExists(schema, name);
            adapter.close();
            return new TestResult("create_drop_table", exists && gone,
                    "created=" + exists + " dropped=" + gone);
        } catch (Exception e) {
            try { adapter.dropRelation(schema, name, AdapterContract.RelationType.TABLE); }
            catch (Exception ignored) {}
            return new TestResult("create_drop_table", false, e.getMessage());
        }
    }

    static TestResult testCreateAndDropView(AdapterContract adapter, String schema) {
        String name = "__espresso_compliance_view";
        try {
            adapter.open();
            adapter.createViewAs(schema, name, "SELECT 1 AS id", false);
            boolean exists = adapter.relationExists(schema, name);
            adapter.dropRelation(schema, name, AdapterContract.RelationType.VIEW);
            adapter.close();
            return new TestResult("create_drop_view", exists, "created=" + exists);
        } catch (Exception e) {
            return new TestResult("create_drop_view", false, e.getMessage());
        }
    }

    static TestResult testGetColumns(AdapterContract adapter, String schema) {
        String name = "__espresso_compliance_cols";
        try {
            adapter.open();
            adapter.createTableAs(schema, name,
                    "SELECT 1 AS id, 'hello' AS name, 3.14 AS amount", false);
            var cols = adapter.getColumns(schema, name);
            adapter.dropRelation(schema, name, AdapterContract.RelationType.TABLE);
            adapter.close();

            boolean hasId = cols.stream().anyMatch(c -> c.name().equalsIgnoreCase("id"));
            boolean hasName = cols.stream().anyMatch(c -> c.name().equalsIgnoreCase("name"));
            boolean hasAmount = cols.stream().anyMatch(c -> c.name().equalsIgnoreCase("amount"));
            boolean ok = cols.size() >= 3 && hasId && hasName && hasAmount;
            return new TestResult("get_columns", ok,
                    "columns=" + cols.stream().map(AdapterContract.ColumnInfo::name).toList());
        } catch (Exception e) {
            return new TestResult("get_columns", false, e.getMessage());
        }
    }

    static TestResult testListRelations(AdapterContract adapter, String schema) {
        try {
            adapter.open();
            var relations = adapter.listRelations(schema);
            adapter.close();
            return new TestResult("list_relations", relations != null,
                    "count=" + (relations != null ? relations.size() : "null"));
        } catch (Exception e) {
            return new TestResult("list_relations", false, e.getMessage());
        }
    }

    static TestResult testRelationExists(AdapterContract adapter, String schema) {
        try {
            adapter.open();
            boolean shouldBeFalse = adapter.relationExists(schema, "__nonexistent_xyz_12345");
            adapter.close();
            return new TestResult("relation_exists_negative", !shouldBeFalse,
                    "nonexistent returned " + shouldBeFalse);
        } catch (Exception e) {
            return new TestResult("relation_exists_negative", false, e.getMessage());
        }
    }

    static TestResult testExecuteSelect(AdapterContract adapter) {
        try {
            adapter.open();
            var result = adapter.execute("SELECT 42 AS answer");
            adapter.close();
            boolean ok = !result.rows().isEmpty()
                    && Objects.equals(result.rows().getFirst().get("answer"), 42);
            return new TestResult("execute_select", ok,
                    "rows=" + result.rows().size());
        } catch (Exception e) {
            return new TestResult("execute_select", false, e.getMessage());
        }
    }

    static TestResult testRenameRelation(AdapterContract adapter, String schema) {
        String name = "__espresso_rename_src";
        String newName = "__espresso_rename_dst";
        try {
            adapter.open();
            adapter.createTableAs(schema, name, "SELECT 1 AS id", false);
            adapter.renameRelation(schema, name, newName);
            boolean renamed = adapter.relationExists(schema, newName);
            adapter.dropRelation(schema, newName, AdapterContract.RelationType.TABLE);
            adapter.close();
            return new TestResult("rename_relation", renamed, "renamed=" + renamed);
        } catch (Exception e) {
            return new TestResult("rename_relation", false, e.getMessage());
        }
    }

    static TestResult testReplaceTable(AdapterContract adapter, String schema) {
        String name = "__espresso_replace";
        try {
            adapter.open();
            adapter.createTableAs(schema, name, "SELECT 1 AS id", false);
            adapter.createTableAs(schema, name, "SELECT 2 AS id, 'new' AS col", true);
            var cols = adapter.getColumns(schema, name);
            adapter.dropRelation(schema, name, AdapterContract.RelationType.TABLE);
            adapter.close();
            boolean hasNewCol = cols.stream().anyMatch(c -> c.name().equalsIgnoreCase("col"));
            return new TestResult("replace_table", hasNewCol, "has_new_col=" + hasNewCol);
        } catch (Exception e) {
            return new TestResult("replace_table", false, e.getMessage());
        }
    }
}
