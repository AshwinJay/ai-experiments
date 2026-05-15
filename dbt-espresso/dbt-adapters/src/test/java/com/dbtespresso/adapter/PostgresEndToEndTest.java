package com.dbtespresso.adapter;

import com.dbtespresso.engine.GraphExecutor;
import com.dbtespresso.graph.ModelGraph;
import com.dbtespresso.jinja.JinjaRenderer;
import com.dbtespresso.parser.DbtProjectScanner;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test: scans a mini dbt project, builds the model graph,
 * runs all models in dependency order via GraphExecutor + AdapterModelRunner,
 * and verifies the materialized views exist and return the expected data.
 */
@Tag("integration")
@Testcontainers
class PostgresEndToEndTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void runThreeModelLinearChain() throws IOException {
        Path projectDir = createMiniProject();

        var models = new DbtProjectScanner(projectDir).scanModels();
        assertThat(models).hasSize(3);

        var graph = ModelGraph.fromModels(models);
        assertThat(graph.executionLevels()).hasSize(3);

        var allNames = graph.executionLevels().stream()
                .flatMap(java.util.List::stream)
                .collect(Collectors.toSet());

        var runner = new AdapterModelRunner(
                () -> new PostgresAdapter(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()),
                new JinjaRenderer(),
                "public",
                allNames);

        var summary = new GraphExecutor(graph, runner).execute();
        assertThat(summary.allSucceeded())
                .withFailMessage(() -> "Model failures: " + summary.failures()
                        .stream().map(r -> r.modelName() + ": " + r.error()).toList())
                .isTrue();

        // Verify all three relations materialized and data is correct
        var verify = new PostgresAdapter(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        verify.open();
        try {
            assertThat(verify.relationExists("public", "stg_items")).as("stg_items").isTrue();
            assertThat(verify.relationExists("public", "mart_items")).as("mart_items").isTrue();
            assertThat(verify.relationExists("public", "mart_item_summary")).as("mart_item_summary").isTrue();

            var result = verify.execute("SELECT cnt FROM public.mart_item_summary");
            assertThat(result.rows()).hasSize(1);
            assertThat(((Number) result.rows().getFirst().get("cnt")).longValue()).isEqualTo(1L);
        } finally {
            verify.close();
        }
    }

    private static Path createMiniProject() throws IOException {
        Path dir = Files.createTempDirectory("dbt-e2e-");
        Path models = Files.createDirectory(dir.resolve("models"));
        Files.writeString(models.resolve("stg_items.sql"),
                "SELECT 1 AS id, 'widget' AS name");
        Files.writeString(models.resolve("mart_items.sql"),
                "SELECT id, name FROM {{ ref('stg_items') }}");
        Files.writeString(models.resolve("mart_item_summary.sql"),
                "SELECT COUNT(*) AS cnt FROM {{ ref('mart_items') }}");
        return dir;
    }
}
