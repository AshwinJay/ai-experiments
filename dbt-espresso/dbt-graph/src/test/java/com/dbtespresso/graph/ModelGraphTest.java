package com.dbtespresso.graph;

import com.dbtespresso.jinja.Dependency;
import com.dbtespresso.parser.ParsedModel;
import com.dbtespresso.parser.ParsedModel.ResourceType;
import org.junit.jupiter.api.*;

import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class ModelGraphTest {

    // Helper to build a ParsedModel with given refs
    static ParsedModel model(String name, String... refs) {
        List<Dependency> deps = new ArrayList<>();
        for (String ref : refs) {
            if (ref.startsWith("source:")) {
                String[] parts = ref.substring(7).split("\\.");
                deps.add(new Dependency.SourceRef(parts[0], parts[1]));
            } else {
                deps.add(new Dependency.ModelRef(ref));
            }
        }
        return new ParsedModel(name, ResourceType.MODEL,
                Path.of("models/" + name + ".sql"), "SELECT 1",
                deps, Map.of());
    }

    // === The jaffle shop graph ===
    //
    //   source:jaffle_shop.orders ─(not in graph)
    //   source:jaffle_shop.customers ─(not in graph)
    //   source:stripe.payments ─(not in graph)
    //
    //   stg_orders ──────┐
    //   stg_customers ───┼──→ orders ──→ customer_orders
    //   stg_payments ────┘         ↑
    //   stg_customers ─────────────┘ (customer_orders also refs stg_customers)

    List<ParsedModel> jaffleShop() {
        return List.of(
                model("stg_orders", "source:jaffle_shop.orders"),
                model("stg_customers", "source:jaffle_shop.customers"),
                model("stg_payments", "source:stripe.payments"),
                model("orders", "stg_orders", "stg_customers", "stg_payments"),
                model("customer_orders", "stg_customers", "orders")
        );
    }

    @Nested class Construction {
        @Test void buildsFromJaffleShop() {
            var graph = ModelGraph.fromModels(jaffleShop());
            assertThat(graph.size()).isEqualTo(5);
        }

        @Test void tracksSources() {
            var graph = ModelGraph.fromModels(jaffleShop());
            assertThat(graph.sourceNodes()).containsExactlyInAnyOrder(
                    "source:jaffle_shop.orders",
                    "source:jaffle_shop.customers",
                    "source:stripe.payments"
            );
        }

        @Test void rejectsDuplicateNames() {
            assertThatThrownBy(() -> ModelGraph.fromModels(List.of(
                    model("a"), model("a")
            ))).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Duplicate");
        }

        @Test void rejectsDanglingRef() {
            assertThatThrownBy(() -> ModelGraph.fromModels(List.of(
                    model("a", "nonexistent")
            ))).isInstanceOf(DanglingRefException.class)
                    .hasMessageContaining("nonexistent");
        }
    }

    @Nested class TopologicalSort {
        @Test void producesValidOrder() {
            var graph = ModelGraph.fromModels(jaffleShop());
            var sorted = graph.topologicalSort();

            assertThat(sorted).hasSize(5);
            // All staging models must come before orders
            assertThat(sorted.indexOf("stg_orders")).isLessThan(sorted.indexOf("orders"));
            assertThat(sorted.indexOf("stg_customers")).isLessThan(sorted.indexOf("orders"));
            assertThat(sorted.indexOf("stg_payments")).isLessThan(sorted.indexOf("orders"));
            // orders must come before customer_orders
            assertThat(sorted.indexOf("orders")).isLessThan(sorted.indexOf("customer_orders"));
        }

        @Test void singleNodeGraph() {
            var graph = ModelGraph.fromModels(List.of(model("lonely")));
            assertThat(graph.topologicalSort()).containsExactly("lonely");
        }

        @Test void linearChain() {
            var graph = ModelGraph.fromModels(List.of(
                    model("a"),
                    model("b", "a"),
                    model("c", "b"),
                    model("d", "c")
            ));
            assertThat(graph.topologicalSort()).containsExactly("a", "b", "c", "d");
        }
    }

    @Nested class ExecutionLevels {
        @Test void jaffleShopLevels() {
            var graph = ModelGraph.fromModels(jaffleShop());
            var levels = graph.executionLevels();

            // Level 0: staging models (no model deps)
            assertThat(levels.get(0)).containsExactlyInAnyOrder(
                    "stg_orders", "stg_customers", "stg_payments");
            // Level 1: orders (depends on all staging)
            assertThat(levels.get(1)).containsExactly("orders");
            // Level 2: customer_orders (depends on orders)
            assertThat(levels.get(2)).containsExactly("customer_orders");
        }

        @Test void wideGraph() {
            // 5 independent models, then 1 that depends on all of them
            var models = new ArrayList<ParsedModel>();
            for (int i = 0; i < 5; i++) models.add(model("leaf_" + i));
            models.add(model("aggregator", "leaf_0", "leaf_1", "leaf_2", "leaf_3", "leaf_4"));

            var graph = ModelGraph.fromModels(models);
            var levels = graph.executionLevels();

            assertThat(levels).hasSize(2);
            assertThat(levels.get(0)).hasSize(5);
            assertThat(levels.get(1)).containsExactly("aggregator");
        }

        @Test void diamondDependency() {
            //     a
            //    / \
            //   b   c
            //    \ /
            //     d
            var graph = ModelGraph.fromModels(List.of(
                    model("a"),
                    model("b", "a"),
                    model("c", "a"),
                    model("d", "b", "c")
            ));
            var levels = graph.executionLevels();

            assertThat(levels).hasSize(3);
            assertThat(levels.get(0)).containsExactly("a");
            assertThat(levels.get(1)).containsExactlyInAnyOrder("b", "c");
            assertThat(levels.get(2)).containsExactly("d");
        }
    }

    @Nested class CycleDetection {
        @Test void detectsDirectCycle() {
            assertThatThrownBy(() -> ModelGraph.fromModels(List.of(
                    model("a", "b"),
                    model("b", "a")
            ))).isInstanceOf(CycleDetectedException.class);
        }

        @Test void detectsTransitiveCycle() {
            assertThatThrownBy(() -> ModelGraph.fromModels(List.of(
                    model("a", "c"),
                    model("b", "a"),
                    model("c", "b")
            ))).isInstanceOf(CycleDetectedException.class);
        }

        @Test void noCycleInDiamond() {
            // Diamond is NOT a cycle
            assertThatCode(() -> ModelGraph.fromModels(List.of(
                    model("a"),
                    model("b", "a"),
                    model("c", "a"),
                    model("d", "b", "c")
            ))).doesNotThrowAnyException();
        }
    }

    @Nested class AncestorsAndDescendants {
        @Test void ancestorsOfCustomerOrders() {
            var graph = ModelGraph.fromModels(jaffleShop());
            var ancestors = graph.ancestors("customer_orders");
            assertThat(ancestors).containsExactlyInAnyOrder(
                    "stg_customers", "orders", "stg_orders", "stg_payments");
        }

        @Test void descendantsOfStgOrders() {
            var graph = ModelGraph.fromModels(jaffleShop());
            var desc = graph.descendants("stg_orders");
            assertThat(desc).containsExactlyInAnyOrder("orders", "customer_orders");
        }

        @Test void leafHasNoAncestors() {
            var graph = ModelGraph.fromModels(jaffleShop());
            assertThat(graph.ancestors("stg_orders")).isEmpty();
        }

        @Test void tipHasNoDescendants() {
            var graph = ModelGraph.fromModels(jaffleShop());
            assertThat(graph.descendants("customer_orders")).isEmpty();
        }
    }

    @Nested class Selection {
        @Test void selectWithUpstream() {
            var graph = ModelGraph.fromModels(jaffleShop());
            // dbt's --select +customer_orders
            var selected = graph.select(List.of("customer_orders"), true, false);
            assertThat(selected).containsExactlyInAnyOrder(
                    "customer_orders", "orders",
                    "stg_orders", "stg_customers", "stg_payments");
        }

        @Test void selectWithDownstream() {
            var graph = ModelGraph.fromModels(jaffleShop());
            // dbt's --select stg_orders+
            var selected = graph.select(List.of("stg_orders"), false, true);
            assertThat(selected).containsExactlyInAnyOrder(
                    "stg_orders", "orders", "customer_orders");
        }

        @Test void selectExact() {
            var graph = ModelGraph.fromModels(jaffleShop());
            var selected = graph.select(List.of("orders"), false, false);
            assertThat(selected).containsExactly("orders");
        }
    }

    @Nested class DependencyQueries {
        @Test void directDependencies() {
            var graph = ModelGraph.fromModels(jaffleShop());
            assertThat(graph.dependenciesOf("orders"))
                    .containsExactlyInAnyOrder("stg_orders", "stg_customers", "stg_payments");
        }

        @Test void directDependents() {
            var graph = ModelGraph.fromModels(jaffleShop());
            assertThat(graph.dependentsOf("orders"))
                    .containsExactly("customer_orders");
        }
    }
}
