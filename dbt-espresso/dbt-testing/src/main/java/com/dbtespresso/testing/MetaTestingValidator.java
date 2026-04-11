package com.dbtespresso.testing;

import com.dbtespresso.parser.ParsedModel;

import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Validates that models meet their configured test and documentation requirements.
 * This is the dbt_meta_testing compatibility layer.
 *
 * <h2>How dbt_meta_testing works</h2>
 * In dbt_project.yml, you configure requirements like:
 * <pre>
 * models:
 *   my_project:
 *     +required_docs: true
 *     marts:
 *       +required_tests: {"unique.*|not_null": 1}
 * </pre>
 *
 * Then you run `dbt run-operation required_tests` and it checks whether every
 * model has at least the required number of tests matching each regex pattern.
 *
 * <h2>What dbt-espresso must provide</h2>
 * <ol>
 *   <li>A way to query the graph: which tests are applied to which models</li>
 *   <li>Regex matching of test names against required patterns</li>
 *   <li>Column-level documentation coverage checks</li>
 * </ol>
 */
public final class MetaTestingValidator {

    private MetaTestingValidator() {}

    /**
     * Test coverage requirement: a regex pattern and minimum count.
     * Example: {"unique.*|not_null": 1} means at least 1 test matching "unique.*" or "not_null".
     */
    public record TestRequirement(String pattern, int minimumCount) {
        public TestRequirement {
            if (minimumCount < 0)
                throw new IllegalArgumentException("minimumCount must be >= 0");
            Pattern.compile(pattern); // validate regex
        }
    }

    /**
     * A single coverage violation.
     */
    public record Violation(
            String modelName,
            ViolationType type,
            String detail
    ) {
        public enum ViolationType { INSUFFICIENT_TESTS, MISSING_DOCUMENTATION }
    }

    /**
     * Validate that models meet their required_tests configuration.
     *
     * @param models       all parsed models
     * @param appliedTests map of model name → list of test names applied to that model
     * @param requirements map of model name → list of test requirements
     * @return list of violations (empty = all models compliant)
     */
    public static List<Violation> validateTestCoverage(
            List<ParsedModel> models,
            Map<String, List<String>> appliedTests,
            Map<String, List<TestRequirement>> requirements
    ) {
        List<Violation> violations = new ArrayList<>();

        for (var model : models) {
            List<TestRequirement> reqs = requirements.get(model.name());
            if (reqs == null) continue;

            List<String> tests = appliedTests.getOrDefault(model.name(), List.of());

            for (var req : reqs) {
                Pattern p = Pattern.compile(req.pattern());
                long matchCount = tests.stream()
                        .filter(testName -> p.matcher(testName).matches())
                        .count();

                if (matchCount < req.minimumCount()) {
                    violations.add(new Violation(
                            model.name(),
                            Violation.ViolationType.INSUFFICIENT_TESTS,
                            "Test pattern '%s': got %d, expected at least %d"
                                    .formatted(req.pattern(), matchCount, req.minimumCount())
                    ));
                }
            }
        }

        return violations;
    }

    /**
     * Validate that models meet their required_docs configuration.
     *
     * @param models           all parsed models
     * @param documentedColumns map of model name → set of documented column names
     * @param actualColumns     map of model name → set of actual column names in warehouse
     * @param modelsRequiringDocs set of model names where +required_docs: true
     * @return list of violations
     */
    public static List<Violation> validateDocCoverage(
            List<ParsedModel> models,
            Map<String, Set<String>> documentedColumns,
            Map<String, Set<String>> actualColumns,
            Set<String> modelsRequiringDocs
    ) {
        List<Violation> violations = new ArrayList<>();

        for (var model : models) {
            if (!modelsRequiringDocs.contains(model.name())) continue;

            Set<String> documented = documentedColumns.getOrDefault(model.name(), Set.of());
            Set<String> actual = actualColumns.getOrDefault(model.name(), Set.of());

            Set<String> undocumented = actual.stream()
                    .filter(col -> !documented.contains(col.toLowerCase()))
                    .collect(Collectors.toSet());

            if (!undocumented.isEmpty()) {
                violations.add(new Violation(
                        model.name(),
                        Violation.ViolationType.MISSING_DOCUMENTATION,
                        "Undocumented columns: " + undocumented
                ));
            }
        }

        return violations;
    }
}
