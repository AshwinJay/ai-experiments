package com.dbtespresso.testing;

import com.dbtespresso.parser.ParsedModel;
import com.dbtespresso.parser.ParsedModel.ResourceType;
import com.dbtespresso.testing.SchemaFile.*;
import com.dbtespresso.testing.UnitTestDefinition.*;
import org.junit.jupiter.api.*;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class TestingModuleTest {

    // ==================== UnitTestCompiler ====================

    @Nested class UnitTestCompilerTest {

        @Test void extractsRefName() {
            assertThat(UnitTestCompiler.extractRefName("ref('stg_orders')"))
                    .isEqualTo("stg_orders");
            assertThat(UnitTestCompiler.extractRefName("ref(\"stg_orders\")"))
                    .isEqualTo("stg_orders");
        }

        @Test void extractsSourceName() {
            assertThat(UnitTestCompiler.extractRefName("source('raw', 'orders')"))
                    .isEqualTo("raw__orders");
        }

        @Test void rejectsInvalidRef() {
            assertThatThrownBy(() -> UnitTestCompiler.extractRefName("garbage"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test void csvToSelectUnion() {
            String csv = """
                    id,name,amount
                    1,alice,100
                    2,bob,200""";
            String sql = UnitTestCompiler.csvToSelectUnion(csv);

            assertThat(sql).contains("SELECT 1 AS id, 'alice' AS name, 100 AS amount");
            assertThat(sql).contains("UNION ALL");
            assertThat(sql).contains("SELECT 2 AS id, 'bob' AS name, 200 AS amount");
        }

        @Test void csvHandlesNullAndBoolean() {
            String csv = """
                    id,active,note
                    1,true,null""";
            String sql = UnitTestCompiler.csvToSelectUnion(csv);

            assertThat(sql).contains("true AS active");
            assertThat(sql).contains("NULL AS note");
        }

        @Test void compilesFullUnitTest() {
            String modelSql = """
                    {{ config(materialized='table') }}
                    SELECT o.id, c.name
                    FROM {{ ref('stg_orders') }} o
                    JOIN {{ ref('stg_customers') }} c ON o.cid = c.id
                    """;

            var test = new UnitTestDefinition(
                    "test_join",
                    "orders",
                    List.of(
                            new MockInput("ref('stg_orders')", MockInput.InputFormat.SQL,
                                    "SELECT 1 AS id, 100 AS cid", null),
                            new MockInput("ref('stg_customers')", MockInput.InputFormat.SQL,
                                    "SELECT 100 AS id, 'Alice' AS name", null)
                    ),
                    new ExpectedOutput(MockInput.InputFormat.SQL,
                            "SELECT 1 AS id, 'Alice' AS name", null),
                    Map.of()
            );

            String compiled = UnitTestCompiler.compile(modelSql, test);

            // Should contain mock CTEs
            assertThat(compiled).contains("__dbt_espresso_mock__stg_orders");
            assertThat(compiled).contains("__dbt_espresso_mock__stg_customers");
            // Should NOT contain Jinja
            assertThat(compiled).doesNotContain("{{");
            assertThat(compiled).doesNotContain("ref(");
            // Should have valid WITH clause structure
            assertThat(compiled.strip().toUpperCase()).startsWith("WITH");
        }

        @Test void compilesModelWithExistingCtes() {
            String modelSql = """
                    {{ config(materialized='table') }}
                    WITH recent AS (
                        SELECT * FROM {{ ref('stg_orders') }} WHERE status = 'new'
                    )
                    SELECT * FROM recent
                    """;

            var test = new UnitTestDefinition("test_cte", "orders",
                    List.of(new MockInput("ref('stg_orders')", MockInput.InputFormat.SQL,
                            "SELECT 1 AS id, 'new' AS status", null)),
                    new ExpectedOutput(MockInput.InputFormat.SQL,
                            "SELECT 1 AS id, 'new' AS status", null),
                    Map.of());

            String compiled = UnitTestCompiler.compile(modelSql, test);

            // Mock CTE should come before the model's CTE
            int mockIdx = compiled.indexOf("__dbt_espresso_mock__stg_orders");
            int recentIdx = compiled.indexOf("recent AS");
            assertThat(mockIdx).isLessThan(recentIdx);
        }

        @Test void compilesDiffQuery() {
            String diff = UnitTestCompiler.compileDiffQuery(
                    "SELECT 1 AS id", "SELECT 1 AS id");
            assertThat(diff).contains("__actual");
            assertThat(diff).contains("__expected");
            assertThat(diff).contains("EXCEPT");
        }
    }

    // ==================== GenericTestCompiler ====================

    @Nested class GenericTestCompilerTest {

        @Test void compilesNotNull() {
            var test = new GenericTest("not_null", null, "orders", "order_id",
                    Map.of(), null, Map.of());
            String sql = GenericTestCompiler.compile(test, "analytics.orders");
            assertThat(sql).contains("IS NULL");
            assertThat(sql).contains("analytics.orders");
        }

        @Test void compilesUnique() {
            var test = new GenericTest("unique", null, "orders", "order_id",
                    Map.of(), null, Map.of());
            String sql = GenericTestCompiler.compile(test, "analytics.orders");
            assertThat(sql).contains("GROUP BY");
            assertThat(sql).contains("HAVING COUNT(*) > 1");
        }

        @Test void compilesAcceptedValues() {
            var test = new GenericTest("accepted_values", null, "orders", "status",
                    Map.of("values", List.of("new", "shipped", "returned")),
                    null, Map.of());
            String sql = GenericTestCompiler.compile(test, "analytics.orders");
            assertThat(sql).contains("NOT IN");
            assertThat(sql).contains("'new'");
            assertThat(sql).contains("'shipped'");
        }

        @Test void compilesRelationships() {
            var test = new GenericTest("relationships", null, "orders", "customer_id",
                    Map.of("to", "analytics.customers", "field", "id"),
                    null, Map.of());
            String sql = GenericTestCompiler.compile(test, "analytics.orders");
            assertThat(sql).contains("LEFT JOIN");
            assertThat(sql).contains("analytics.customers");
            assertThat(sql).contains("parent.id IS NULL");
        }

        @Test void compilesExpectBetween() {
            var test = new GenericTest("expect_column_values_to_be_between",
                    "dbt_expectations", "orders", "amount",
                    Map.of("min_value", 0, "max_value", 10000),
                    null, Map.of());
            String sql = GenericTestCompiler.compile(test, "analytics.orders");
            assertThat(sql).contains("amount < 0");
            assertThat(sql).contains("amount > 10000");
        }

        @Test void compilesExpectRowCount() {
            var test = new GenericTest("expect_table_row_count_to_equal",
                    "dbt_expectations", "orders", null,
                    Map.of("value", 1000),
                    null, Map.of());
            String sql = GenericTestCompiler.compile(test, "analytics.orders");
            assertThat(sql).contains("HAVING COUNT(*) != 1000");
        }

        @Test void compilesExpectRegex() {
            var test = new GenericTest("expect_column_values_to_match_regex",
                    "dbt_expectations", "orders", "email",
                    Map.of("regex", "^[a-z]+@[a-z]+\\.[a-z]+$"),
                    null, Map.of());
            String sql = GenericTestCompiler.compile(test, "analytics.orders");
            assertThat(sql).contains("REGEXP_LIKE");
        }

        @Test void handlesRowCondition() {
            var test = new GenericTest("not_null", null, "orders", "email",
                    Map.of("row_condition", "status = 'active'"),
                    null, Map.of());
            String sql = GenericTestCompiler.compile(test, "analytics.orders");
            assertThat(sql).contains("AND (status = 'active')");
        }

        @Test void rejectsUnknownTest() {
            var test = new GenericTest("some_custom_test", "my_package",
                    "orders", "id", Map.of(), null, Map.of());
            assertThatThrownBy(() -> GenericTestCompiler.compile(test, "orders"))
                    .isInstanceOf(UnsupportedTestException.class)
                    .hasMessageContaining("Jinja macro");
        }

        @Test void warnSeverityDoesNotAffectSql() {
            var test = new GenericTest("not_null", null, "orders", "id",
                    Map.of(), GenericTest.TestSeverity.WARN, Map.of());
            // SQL is the same regardless of severity; severity affects
            // how the runner interprets the result (fail vs warn)
            String sql = GenericTestCompiler.compile(test, "analytics.orders");
            assertThat(sql).contains("IS NULL");
        }
    }

    // ==================== MetaTestingValidator ====================

    @Nested class MetaTestingValidatorTest {

        static ParsedModel model(String name) {
            return new ParsedModel(name, ResourceType.MODEL,
                    Path.of(name + ".sql"), "SELECT 1", List.of(), Map.of());
        }

        @Test void passesWhenRequirementsMet() {
            var models = List.of(model("orders"));
            var appliedTests = Map.of("orders", List.of("not_null", "unique"));
            var requirements = Map.of("orders",
                    List.of(new MetaTestingValidator.TestRequirement("not_null", 1),
                            new MetaTestingValidator.TestRequirement("unique", 1)));

            var violations = MetaTestingValidator.validateTestCoverage(
                    models, appliedTests, requirements);
            assertThat(violations).isEmpty();
        }

        @Test void failsWhenInsufficientTests() {
            var models = List.of(model("orders"));
            var appliedTests = Map.of("orders", List.of("not_null"));
            var requirements = Map.of("orders",
                    List.of(new MetaTestingValidator.TestRequirement("unique.*|not_null", 2)));

            var violations = MetaTestingValidator.validateTestCoverage(
                    models, appliedTests, requirements);
            assertThat(violations).hasSize(1);
            assertThat(violations.getFirst().type())
                    .isEqualTo(MetaTestingValidator.Violation.ViolationType.INSUFFICIENT_TESTS);
        }

        @Test void regexMatchesTestNames() {
            var models = List.of(model("orders"));
            var appliedTests = Map.of("orders",
                    List.of("unique", "unique_combination_of_columns"));
            var requirements = Map.of("orders",
                    List.of(new MetaTestingValidator.TestRequirement("unique.*", 2)));

            var violations = MetaTestingValidator.validateTestCoverage(
                    models, appliedTests, requirements);
            assertThat(violations).isEmpty(); // both match "unique.*"
        }

        @Test void modelsWithoutRequirementsAreSkipped() {
            var models = List.of(model("orders"), model("customers"));
            var appliedTests = Map.<String, List<String>>of();
            var requirements = Map.of("orders",
                    List.of(new MetaTestingValidator.TestRequirement("not_null", 1)));

            var violations = MetaTestingValidator.validateTestCoverage(
                    models, appliedTests, requirements);
            // Only orders should be checked
            assertThat(violations).hasSize(1);
            assertThat(violations.getFirst().modelName()).isEqualTo("orders");
        }

        @Test void docCoverageFindsUndocumentedColumns() {
            var models = List.of(model("orders"));
            var documented = Map.of("orders", Set.of("id", "status"));
            var actual = Map.of("orders", Set.of("id", "status", "amount", "created_at"));
            var requiring = Set.of("orders");

            var violations = MetaTestingValidator.validateDocCoverage(
                    models, documented, actual, requiring);
            assertThat(violations).hasSize(1);
            assertThat(violations.getFirst().detail())
                    .contains("amount").contains("created_at");
        }

        @Test void docCoveragePassesWhenFullyDocumented() {
            var models = List.of(model("orders"));
            var documented = Map.of("orders", Set.of("id", "status"));
            var actual = Map.of("orders", Set.of("id", "status"));
            var requiring = Set.of("orders");

            var violations = MetaTestingValidator.validateDocCoverage(
                    models, documented, actual, requiring);
            assertThat(violations).isEmpty();
        }
    }

    // ==================== SchemaFileParser ====================

    @Nested class SchemaFileParserTest {

        SchemaFile schema;

        @BeforeEach
        void load() throws Exception {
            try (InputStream in = getClass().getResourceAsStream("/sample_schema.yml")) {
                schema = SchemaFileParser.parse(in);
            }
        }

        @Test void parsesTwoModels() {
            assertThat(schema.models()).hasSize(2);
        }

        @Test void parsesModelName() {
            assertThat(schema.models().getFirst().name()).isEqualTo("orders");
        }

        @Test void parsesModelDescription() {
            assertThat(schema.models().getFirst().description())
                    .contains("Order fact table");
        }

        @Test void parsesModelConfig() {
            assertThat(schema.models().getFirst().config())
                    .containsEntry("materialized", "table");
        }

        @Test void parsesFourColumns() {
            assertThat(schema.models().getFirst().columns()).hasSize(4);
        }

        @Test void parsesSimpleColumnTests() {
            ColumnSchema orderIdCol = schema.models().getFirst().columns().getFirst();
            assertThat(orderIdCol.name()).isEqualTo("order_id");
            assertThat(orderIdCol.tests()).hasSize(2);
            assertThat(orderIdCol.tests().stream().map(GenericTest::testName))
                    .containsExactlyInAnyOrder("unique", "not_null");
        }

        @Test void parsesAcceptedValuesWithArgs() {
            ColumnSchema statusCol = schema.models().getFirst().columns().get(1);
            assertThat(statusCol.name()).isEqualTo("status");
            GenericTest test = statusCol.tests().getFirst();
            assertThat(test.testName()).isEqualTo("accepted_values");
            assertThat(test.isBuiltin()).isTrue();
            @SuppressWarnings("unchecked")
            List<String> values = (List<String>) test.arguments().get("values");
            assertThat(values).containsExactly("placed", "shipped", "completed", "returned");
        }

        @Test void parsesRelationshipsTest() {
            ColumnSchema customerIdCol = schema.models().getFirst().columns().get(2);
            GenericTest relTest = customerIdCol.tests().stream()
                    .filter(t -> t.testName().equals("relationships")).findFirst().orElseThrow();
            assertThat(relTest.arguments()).containsKey("to");
            assertThat(relTest.arguments()).containsEntry("field", "customer_id");
        }

        @Test void parsesExpectationTest() {
            ColumnSchema amountCol = schema.models().getFirst().columns().get(3);
            GenericTest expectTest = amountCol.tests().stream()
                    .filter(GenericTest::isExpectation).findFirst().orElseThrow();
            assertThat(expectTest.testName()).isEqualTo("expect_column_values_to_be_between");
            assertThat(expectTest.packageName()).isEqualTo("dbt_expectations");
            assertThat(expectTest.arguments()).containsEntry("min_value", 0);
            assertThat(expectTest.arguments()).containsEntry("max_value", 100000);
        }

        @Test void parsesModelLevelTest() {
            List<GenericTest> modelTests = schema.models().getFirst().modelTests();
            assertThat(modelTests).hasSize(1);
            assertThat(modelTests.getFirst().testName())
                    .isEqualTo("expect_table_row_count_to_be_between");
        }

        @Test void parsesSeverityOverride() {
            ModelSchema stgOrders = schema.models().get(1);
            ColumnSchema statusCol = stgOrders.columns().get(1);
            GenericTest avTest = statusCol.tests().getFirst();
            assertThat(avTest.severity()).isEqualTo(GenericTest.TestSeverity.WARN);
        }

        @Test void parsesSource() {
            assertThat(schema.sources()).hasSize(1);
            assertThat(schema.sources().getFirst().name()).isEqualTo("jaffle_shop");
        }

        @Test void parsesSourceTable() {
            SourceTableSchema table = schema.sources().getFirst().tables().getFirst();
            assertThat(table.tableName()).isEqualTo("orders");
            assertThat(table.sourceName()).isEqualTo("jaffle_shop");
        }

        @Test void parsesSourceColumnTests() {
            SourceTableSchema table = schema.sources().getFirst().tables().getFirst();
            ColumnSchema idCol = table.columns().getFirst();
            assertThat(idCol.name()).isEqualTo("id");
            assertThat(idCol.tests()).hasSize(2);
        }

        @Test void parsesTwoUnitTests() {
            assertThat(schema.unitTests()).hasSize(2);
        }

        @Test void parsesUnitTestName() {
            assertThat(schema.unitTests().getFirst().name()).isEqualTo("test_orders_joins_correctly");
            assertThat(schema.unitTests().getFirst().modelName()).isEqualTo("orders");
        }

        @Test void parsesUnitTestGivenInputs() {
            UnitTestDefinition ut = schema.unitTests().getFirst();
            assertThat(ut.given()).hasSize(3);
        }

        @Test void parsesCsvFormatFromExplicitTag() {
            MockInput csvInput = schema.unitTests().getFirst().given().getFirst();
            assertThat(csvInput.input()).isEqualTo("ref('stg_orders')");
            assertThat(csvInput.format()).isEqualTo(MockInput.InputFormat.CSV);
            assertThat(csvInput.rows()).contains("order_id,customer_id");
        }

        @Test void infersDictFormatFromListRows() {
            MockInput dictInput = schema.unitTests().getFirst().given().get(1);
            assertThat(dictInput.input()).isEqualTo("ref('stg_customers')");
            assertThat(dictInput.format()).isEqualTo(MockInput.InputFormat.DICT);
            assertThat(dictInput.rows()).contains("customer_id");
        }

        @Test void parsesExpectedOutput() {
            UnitTestDefinition ut = schema.unitTests().getFirst();
            assertThat(ut.expected()).isNotNull();
            assertThat(ut.expected().rows()).contains("order_id");
        }

        @Test void genericTestsFlattenAll() {
            List<GenericTest> all = schema.genericTests();
            // orders: 2+1+2+2(amount)+1(model) = 8; stg_orders: 2+1 = 3; source: 2+1 = 3  →  14
            assertThat(all).hasSizeGreaterThanOrEqualTo(10);
        }

        @Test void parsesFromYamlString() throws Exception {
            String yaml = """
                    version: 2
                    models:
                      - name: my_model
                        columns:
                          - name: id
                            data_tests:
                              - not_null
                    """;
            SchemaFile sf = SchemaFileParser.parseString(yaml);
            assertThat(sf.models()).hasSize(1);
            assertThat(sf.models().getFirst().columns().getFirst().tests()).hasSize(1);
        }
    }

    // ==================== AdapterContract ====================

    @Nested class AdapterContractTest {

        @Test void genericTestIdentifiesBuiltins() {
            var test = new GenericTest("unique", null, "m", "c", Map.of(), null, Map.of());
            assertThat(test.isBuiltin()).isTrue();
            assertThat(test.isExpectation()).isFalse();
        }

        @Test void genericTestIdentifiesExpectations() {
            var test = new GenericTest("expect_column_values_to_be_between",
                    "dbt_expectations", "m", "c", Map.of(), null, Map.of());
            assertThat(test.isBuiltin()).isFalse();
            assertThat(test.isExpectation()).isTrue();
        }

        @Test void qualifiedName() {
            var builtin = new GenericTest("not_null", null, "m", "c", Map.of(), null, Map.of());
            assertThat(builtin.qualifiedName()).isEqualTo("not_null");

            var expectation = new GenericTest("expect_foo", "dbt_expectations",
                    "m", "c", Map.of(), null, Map.of());
            assertThat(expectation.qualifiedName()).isEqualTo("dbt_expectations.expect_foo");
        }
    }
}
