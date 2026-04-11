package com.dbtespresso.parser;

import com.dbtespresso.jinja.Dependency;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class DbtProjectScannerTest {

    Path sampleProject() {
        var url = getClass().getClassLoader().getResource("sample_project");
        assertThat(url).isNotNull();
        return Path.of(url.getPath());
    }

    @Nested class ScanSampleProject {
        @Test void findsAllModels() {
            var models = new DbtProjectScanner(sampleProject()).scanModels();
            assertThat(models).extracting(ParsedModel::name)
                    .containsExactlyInAnyOrder(
                            "stg_orders", "stg_customers", "stg_payments",
                            "orders", "customer_orders");
        }

        @Test void stagingModelsHaveSourceDeps() {
            var stg = findByName("stg_orders");
            assertThat(stg.sourceRefs())
                    .containsExactly(new Dependency.SourceRef("jaffle_shop", "orders"));
            assertThat(stg.modelRefs()).isEmpty();
        }

        @Test void martDependsOnStaging() {
            var orders = findByName("orders");
            assertThat(orders.modelRefs()).extracting(Dependency.ModelRef::modelName)
                    .containsExactlyInAnyOrder("stg_orders", "stg_customers", "stg_payments");
        }

        @Test void customerOrdersDependsOnOrdersAndStaging() {
            var co = findByName("customer_orders");
            assertThat(co.modelRefs()).extracting(Dependency.ModelRef::modelName)
                    .containsExactlyInAnyOrder("stg_customers", "orders");
        }

        @Test void extractsConfig() {
            assertThat(findByName("stg_orders").materialized()).isEqualTo("view");
            assertThat(findByName("orders").materialized()).isEqualTo("table");
            assertThat(findByName("orders").config().get("schema")).isEqualTo("analytics");
        }

        private ParsedModel findByName(String name) {
            return new DbtProjectScanner(sampleProject()).scanModels().stream()
                    .filter(m -> m.name().equals(name)).findFirst()
                    .orElseThrow(() -> new AssertionError("Not found: " + name));
        }
    }

    @Nested class EdgeCases {
        @Test void emptyProject(@TempDir Path tmp) {
            assertThat(new DbtProjectScanner(tmp).scanModels()).isEmpty();
        }

        @Test void skipsNonSql(@TempDir Path tmp) throws IOException {
            var dir = tmp.resolve("models");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("schema.yml"), "version: 2");
            Files.writeString(dir.resolve("real.sql"), "SELECT * FROM {{ ref('x') }}");
            var models = new DbtProjectScanner(tmp).scanModels();
            assertThat(models).hasSize(1);
            assertThat(models.getFirst().name()).isEqualTo("real");
        }

        @Test void rejectsNonDirectory() {
            assertThatThrownBy(() -> new DbtProjectScanner(Path.of("/nonexistent")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested class ConfigExtractorTest {
        @Test void extractsAllKeys() {
            var cfg = ConfigExtractor.extract(
                    "{{ config(materialized='table', schema='analytics', tags=['a']) }}");
            assertThat(cfg).containsEntry("materialized", "table")
                    .containsEntry("schema", "analytics")
                    .containsKey("tags");
        }

        @Test void noConfig() {
            assertThat(ConfigExtractor.extract("SELECT 1")).isEmpty();
        }
    }
}
