package com.dbtespresso.testing;

import java.util.*;

/**
 * A unit test definition as declared in schema.yml:
 *
 * <pre>
 * unit_tests:
 *   - name: test_is_valid_email
 *     model: dim_customers
 *     given:
 *       - input: ref('stg_customers')
 *         format: csv
 *         rows: |
 *           email,domain
 *           good@test.com,test.com
 *       - input: ref('email_domains')
 *         rows:
 *           - {tld: test.com}
 *     expect:
 *       rows:
 *         - {email: good@test.com, is_valid: true}
 * </pre>
 *
 * <h2>How unit tests work in dbt</h2>
 * The engine takes the model's SQL, replaces every ref()/source() with a CTE
 * containing the mocked rows, executes the composed query, and compares the
 * result against the expected rows. No real data is needed.
 *
 * <h2>What dbt-espresso must do</h2>
 * <ol>
 *   <li>Parse the YAML into this record</li>
 *   <li>Use the Jinja renderer to identify ref/source calls in the model</li>
 *   <li>Replace each ref/source with a CTE built from {@link MockInput}</li>
 *   <li>Execute the rewritten SQL against the warehouse</li>
 *   <li>Diff the result against {@link #expected}</li>
 * </ol>
 */
public record UnitTestDefinition(
        String name,
        String modelName,
        List<MockInput> given,
        ExpectedOutput expected,
        Map<String, Object> overrides
) {
    public UnitTestDefinition {
        Objects.requireNonNull(name);
        Objects.requireNonNull(modelName);
        given = given != null ? List.copyOf(given) : List.of();
        Objects.requireNonNull(expected);
        overrides = overrides != null ? Map.copyOf(overrides) : Map.of();
    }

    /**
     * A mocked input for a ref() or source() call.
     *
     * @param input       the ref/source expression, e.g. "ref('stg_customers')"
     * @param format      ROW_DICT, CSV, or SQL
     * @param rows        the mock data (format depends on {@code format})
     * @param fixtureName optional reference to an external fixture file
     */
    public record MockInput(
            String input,
            InputFormat format,
            String rows,
            String fixtureName
    ) {
        public enum InputFormat { DICT, CSV, SQL }
    }

    /**
     * The expected output rows for comparison.
     */
    public record ExpectedOutput(
            MockInput.InputFormat format,
            String rows,
            String fixtureName
    ) {}
}
