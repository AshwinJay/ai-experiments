package com.dbtespresso.adapter;

import com.dbtespresso.engine.ExecutionSummary;
import com.dbtespresso.engine.GraphExecutor;
import com.dbtespresso.engine.ModelResult;
import com.dbtespresso.graph.ModelGraph;
import com.dbtespresso.jinja.Dependency;
import com.dbtespresso.jinja.JinjaRenderer;
import com.dbtespresso.parser.ParsedModel;
import com.dbtespresso.parser.ParsedModel.ResourceType;
import com.dbtespresso.qa.AdapterContract;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the full pipeline — parse → graph → render Jinja → execute SQL — against
 * a live Postgres container.  This exercises the path that the adapter compliance
 * test does NOT: that models with {{ ref() }} dependencies are materialized in the
 * correct topological order and produce real tables/views.
 */
@Tag("integration")
@Testcontainers
class PipelineIntegrationTest {

    static final String SCHEMA = "public";

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    AdapterContract openAdapter() {
        var a = new PostgresAdapter(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        a.open();
        return a;
    }

    // ── fixture models ──────────────────────────────────────────────────────────

    static ParsedModel stgOrders() {
        return new ParsedModel(
                "stg_orders", ResourceType.MODEL,
                Path.of("models/stg_orders.sql"),
                "SELECT 1 AS order_id, 'placed'::text AS status",
                List.of(),
                Map.of("materialized", "table"));
    }

    static ParsedModel stgCustomers() {
        return new ParsedModel(
                "stg_customers", ResourceType.MODEL,
                Path.of("models/stg_customers.sql"),
                "SELECT 1 AS customer_id, 'Alice'::text AS name",
                List.of(),
                Map.of("materialized", "table"));
    }

    /** Joins stg_orders and stg_customers via {{ ref() }} — materialized as a view. */
    static ParsedModel orders() {
        return new ParsedModel(
                "orders", ResourceType.MODEL,
                Path.of("models/orders.sql"),
                """
                SELECT o.order_id, c.name AS customer_name
                FROM {{ ref('stg_orders') }} o
                JOIN {{ ref('stg_customers') }} c ON true
                """,
                List.of(
                        new Dependency.ModelRef("stg_orders"),
                        new Dependency.ModelRef("stg_customers")),
                Map.of("materialized", "view"));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    AdapterModelRunner runner(Set<String> modelNames) {
        return new AdapterModelRunner(
                () -> new PostgresAdapter(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()),
                new JinjaRenderer(),
                SCHEMA,
                modelNames);
    }

    void dropAll() {
        var adapter = openAdapter();
        for (String name : List.of("orders", "stg_orders", "stg_customers")) {
            adapter.dropRelation(SCHEMA, name, AdapterContract.RelationType.VIEW);
            adapter.dropRelation(SCHEMA, name, AdapterContract.RelationType.TABLE);
        }
        adapter.close();
    }

    @BeforeEach
    void setUp() { dropAll(); }

    @AfterEach
    void tearDown() { dropAll(); }

    // ── tests ────────────────────────────────────────────────────────────────────

    @Test
    void allModelsSucceed() {
        var models = List.of(stgOrders(), stgCustomers(), orders());
        var graph = ModelGraph.fromModels(models);
        var summary = new GraphExecutor(graph, runner(Set.of("stg_orders", "stg_customers", "orders"))).execute();

        assertThat(summary.allSucceeded())
                .withFailMessage(() -> "Expected all models to succeed but got: " + failureDetails(summary))
                .isTrue();
    }

    @Test
    void tablesAndViewsExistAfterRun() {
        var models = List.of(stgOrders(), stgCustomers(), orders());
        var graph = ModelGraph.fromModels(models);
        new GraphExecutor(graph, runner(Set.of("stg_orders", "stg_customers", "orders"))).execute();

        var adapter = openAdapter();
        try {
            assertThat(adapter.relationExists(SCHEMA, "stg_orders")).as("stg_orders table").isTrue();
            assertThat(adapter.relationExists(SCHEMA, "stg_customers")).as("stg_customers table").isTrue();
            assertThat(adapter.relationExists(SCHEMA, "orders")).as("orders view").isTrue();
        } finally {
            adapter.close();
        }
    }

    @Test
    void refMacrosResolveToCorrectData() {
        var models = List.of(stgOrders(), stgCustomers(), orders());
        var graph = ModelGraph.fromModels(models);
        new GraphExecutor(graph, runner(Set.of("stg_orders", "stg_customers", "orders"))).execute();

        var adapter = openAdapter();
        try {
            var rows = adapter.execute("SELECT order_id, customer_name FROM public.orders").rows();
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst()).containsEntry("customer_name", "Alice");
        } finally {
            adapter.close();
        }
    }

    @Test
    void failedUpstreamSkipsDownstream() {
        // orders_bad references a model that doesn't exist → its SELECT will fail at the DB
        ParsedModel badStaging = new ParsedModel(
                "bad_staging", ResourceType.MODEL,
                Path.of("models/bad_staging.sql"),
                "SELECT * FROM nonexistent_table_xyz",
                List.of(),
                Map.of("materialized", "table"));
        ParsedModel downstream = new ParsedModel(
                "downstream", ResourceType.MODEL,
                Path.of("models/downstream.sql"),
                "SELECT * FROM {{ ref('bad_staging') }}",
                List.of(new Dependency.ModelRef("bad_staging")),
                Map.of("materialized", "view"));

        var models = List.of(badStaging, downstream);
        var graph = ModelGraph.fromModels(models);
        var summary = new GraphExecutor(graph, runner(Set.of("bad_staging", "downstream"))).execute();

        assertThat(summary.results().get("bad_staging").status()).isEqualTo(ModelResult.Status.ERROR);
        assertThat(summary.results().get("downstream").status()).isEqualTo(ModelResult.Status.SKIPPED);
    }

    // ── utils ────────────────────────────────────────────────────────────────────

    static String failureDetails(ExecutionSummary summary) {
        return summary.failures().stream()
                .map(r -> r.modelName() + ": " + r.errorMessage().orElse("unknown"))
                .toList()
                .toString();
    }
}
