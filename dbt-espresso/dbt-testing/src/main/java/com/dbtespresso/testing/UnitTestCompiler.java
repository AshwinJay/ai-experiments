package com.dbtespresso.testing;

import com.dbtespresso.jinja.Dependency;
import com.dbtespresso.jinja.RefExtractor;
import com.dbtespresso.testing.UnitTestDefinition.MockInput;

import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Compiles a unit test into executable SQL by replacing ref()/source() calls
 * with CTEs containing mocked data.
 *
 * <h2>Transformation</h2>
 * Given a model SQL:
 * <pre>
 * SELECT o.id, c.name FROM {{ ref('stg_orders') }} o JOIN {{ ref('stg_customers') }} c ...
 * </pre>
 *
 * And mock inputs for stg_orders and stg_customers, the compiler produces:
 * <pre>
 * WITH __dbt_espresso_mock__stg_orders AS (
 *     SELECT 1 AS id, 100 AS customer_id
 *     UNION ALL SELECT 2 AS id, 200 AS customer_id
 * ),
 * __dbt_espresso_mock__stg_customers AS (
 *     SELECT 100 AS id, 'Alice' AS name
 * )
 * SELECT o.id, c.name
 * FROM __dbt_espresso_mock__stg_orders o
 * JOIN __dbt_espresso_mock__stg_customers c ...
 * </pre>
 *
 * This is exactly how dbt Core's unit testing works under the hood.
 */
public final class UnitTestCompiler {

    private static final String MOCK_PREFIX = "__dbt_espresso_mock__";

    private UnitTestCompiler() {}

    /**
     * Compile a unit test into a self-contained SQL query.
     *
     * @param modelSql the raw Jinja+SQL of the model under test
     * @param test     the unit test definition with mock inputs
     * @return executable SQL with mock CTEs
     */
    public static String compile(String modelSql, UnitTestDefinition test) {
        // Step 1: Build mock CTEs from the given inputs
        Map<String, String> mockCtes = new LinkedHashMap<>();
        Map<String, String> refToMockName = new LinkedHashMap<>();

        for (MockInput mock : test.given()) {
            String refName = extractRefName(mock.input());
            String mockName = MOCK_PREFIX + refName;
            String cteSql = mockInputToSql(mock);
            mockCtes.put(mockName, cteSql);
            refToMockName.put(refName, mockName);
        }

        // Step 2: Replace {{ ref('x') }} and {{ source('a','b') }} with mock CTE names
        String rewrittenSql = replaceRefs(modelSql, refToMockName);

        // Step 3: Strip any existing WITH clause and prepend our mock CTEs
        // Handle models that already use CTEs by merging
        String withClause = mockCtes.entrySet().stream()
                .map(e -> e.getKey() + " AS (\n" + indent(e.getValue()) + "\n)")
                .collect(Collectors.joining(",\n"));

        // If the model SQL itself starts with WITH, merge the CTEs
        String strippedSql = rewrittenSql.strip();
        if (strippedSql.toUpperCase().startsWith("WITH ")) {
            // Remove the "WITH" keyword and prepend our mocks
            String afterWith = strippedSql.substring(4).strip();
            return "WITH " + withClause + ",\n" + afterWith;
        } else {
            return "WITH " + withClause + "\n" + strippedSql;
        }
    }

    /**
     * Build the expected-output SQL for comparison.
     * Returns a SELECT that produces the expected rows.
     */
    public static String compileExpected(UnitTestDefinition.ExpectedOutput expected) {
        return mockDataToSelectUnion(expected.format(), expected.rows());
    }

    /**
     * Build a comparison query that returns rows where actual != expected.
     * If zero rows are returned, the test passes (dbt convention).
     */
    public static String compileDiffQuery(String actualQuery, String expectedQuery) {
        return """
                WITH __actual AS (
                %s
                ),
                __expected AS (
                %s
                )
                (SELECT 'missing_from_actual' AS __diff_type, e.* FROM __expected e
                 EXCEPT
                 SELECT 'missing_from_actual', a.* FROM __actual a)
                UNION ALL
                (SELECT 'unexpected_in_actual' AS __diff_type, a.* FROM __actual a
                 EXCEPT
                 SELECT 'unexpected_in_actual', e.* FROM __expected e)
                """.formatted(indent(actualQuery), indent(expectedQuery));
    }

    // --- Internal helpers ---

    static String extractRefName(String input) {
        // "ref('stg_orders')" → "stg_orders"
        // "source('raw', 'orders')" → "raw__orders"
        var refPattern = Pattern.compile("ref\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");
        var srcPattern = Pattern.compile(
                "source\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*,\\s*['\"]([^'\"]+)['\"]\\s*\\)");

        var srcMatch = srcPattern.matcher(input);
        if (srcMatch.find()) return srcMatch.group(1) + "__" + srcMatch.group(2);

        var refMatch = refPattern.matcher(input);
        if (refMatch.find()) return refMatch.group(1);

        throw new IllegalArgumentException("Cannot parse input reference: " + input);
    }

    static String replaceRefs(String sql, Map<String, String> refToMockName) {
        String result = sql;
        for (var entry : refToMockName.entrySet()) {
            String refName = entry.getKey();
            String mockName = entry.getValue();

            // Replace {{ ref('name') }} with the mock CTE name
            // Handle both single and double quotes, with optional whitespace and filters
            String refPattern = "\\{\\{\\s*ref\\s*\\(\\s*['\"]" + Pattern.quote(refName)
                    + "['\"]\\s*\\)[^}]*\\}\\}";
            result = result.replaceAll(refPattern, mockName);

            // Also replace source('a', 'b') patterns
            if (refName.contains("__")) {
                String[] parts = refName.split("__", 2);
                String srcPattern = "\\{\\{\\s*source\\s*\\(\\s*['\"]" + Pattern.quote(parts[0])
                        + "['\"]\\s*,\\s*['\"]" + Pattern.quote(parts[1])
                        + "['\"]\\s*\\)[^}]*\\}\\}";
                result = result.replaceAll(srcPattern, mockName);
            }
        }
        // Also strip config() blocks
        result = result.replaceAll("\\{\\{[^}]*config\\s*\\([^)]*\\)[^}]*\\}\\}", "");
        return result;
    }

    static String mockInputToSql(MockInput mock) {
        return mockDataToSelectUnion(mock.format(), mock.rows());
    }

    static String mockDataToSelectUnion(MockInput.InputFormat format, String rows) {
        if (format == MockInput.InputFormat.SQL) {
            return rows.strip();
        }
        if (format == MockInput.InputFormat.CSV) {
            return csvToSelectUnion(rows.strip());
        }
        // DICT format — rows are like: "{id: 1, name: alice}"
        // This would be parsed from YAML into actual maps; for now treat as SQL
        return rows.strip();
    }

    /**
     * Convert CSV data into SELECT ... UNION ALL SELECT ... statements.
     *
     * Input:
     *   id,name,amount
     *   1,alice,100
     *   2,bob,200
     *
     * Output:
     *   SELECT 1 AS id, 'alice' AS name, 100 AS amount
     *   UNION ALL SELECT 2 AS id, 'bob' AS name, 200 AS amount
     */
    static String csvToSelectUnion(String csv) {
        String[] lines = csv.split("\n");
        if (lines.length < 2) {
            throw new IllegalArgumentException("CSV must have at least a header and one data row");
        }

        String[] headers = lines[0].strip().split(",");
        List<String> selects = new ArrayList<>();

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) continue;
            String[] values = line.split(",", -1);
            List<String> cols = new ArrayList<>();
            for (int j = 0; j < headers.length && j < values.length; j++) {
                String val = values[j].strip();
                String col = quoteIfNeeded(val) + " AS " + headers[j].strip();
                cols.add(col);
            }
            selects.add("SELECT " + String.join(", ", cols));
        }

        return String.join("\nUNION ALL ", selects);
    }

    private static String quoteIfNeeded(String value) {
        if (value.equalsIgnoreCase("null")) return "NULL";
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) return value;
        try {
            Double.parseDouble(value);
            return value; // numeric
        } catch (NumberFormatException e) {
            return "'" + value.replace("'", "''") + "'";
        }
    }

    private static String indent(String sql) {
        return sql.lines().map(l -> "    " + l).collect(Collectors.joining("\n"));
    }
}
