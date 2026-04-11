package com.dbtespresso.engine;

import com.dbtespresso.graph.ModelGraph;
import com.dbtespresso.jinja.Dependency;
import com.dbtespresso.parser.ParsedModel;
import com.dbtespresso.parser.ParsedModel.ResourceType;
import org.junit.jupiter.api.*;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

class GraphExecutorTest {

    static ParsedModel model(String name, String... refs) {
        List<Dependency> deps = Arrays.stream(refs)
                .map(r -> (Dependency) new Dependency.ModelRef(r)).toList();
        return new ParsedModel(name, ResourceType.MODEL,
                Path.of("models/" + name + ".sql"), "SELECT 1", deps, Map.of());
    }

    /** Runner that always succeeds, recording execution order. */
    static class RecordingRunner implements ModelRunner {
        final List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());
        final Map<String, Long> threadIds = new ConcurrentHashMap<>();

        @Override public ModelResult run(ParsedModel model) {
            executionOrder.add(model.name());
            threadIds.put(model.name(), Thread.currentThread().threadId());
            return ModelResult.success(model.name(), Instant.now(), Duration.ofMillis(1), 10);
        }
    }

    /** Runner that fails on specific models. */
    static class FailingRunner implements ModelRunner {
        final Set<String> failureModels;
        FailingRunner(String... failures) { this.failureModels = Set.of(failures); }

        @Override public ModelResult run(ParsedModel model) {
            if (failureModels.contains(model.name())) {
                return ModelResult.error(model.name(), Instant.now(),
                        Duration.ofMillis(1), "Simulated failure");
            }
            return ModelResult.success(model.name(), Instant.now(), Duration.ofMillis(1), 5);
        }
    }

    /** Runner that sleeps to test concurrency. */
    static class SlowRunner implements ModelRunner {
        final AtomicInteger peakConcurrency = new AtomicInteger(0);
        final AtomicInteger currentConcurrency = new AtomicInteger(0);

        @Override public ModelResult run(ParsedModel model) {
            int current = currentConcurrency.incrementAndGet();
            peakConcurrency.accumulateAndGet(current, Math::max);
            try { Thread.sleep(50); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { currentConcurrency.decrementAndGet(); }
            return ModelResult.success(model.name(), Instant.now(), Duration.ofMillis(50), 1);
        }
    }

    // The jaffle shop graph
    List<ParsedModel> jaffleShop() {
        return List.of(
                model("stg_orders"),
                model("stg_customers"),
                model("stg_payments"),
                model("orders", "stg_orders", "stg_customers", "stg_payments"),
                model("customer_orders", "stg_customers", "orders")
        );
    }

    @Nested class HappyPath {
        @Test void executesAllModels() {
            var graph = ModelGraph.fromModels(jaffleShop());
            var runner = new RecordingRunner();
            var summary = new GraphExecutor(graph, runner).execute();

            assertThat(summary.allSucceeded()).isTrue();
            assertThat(summary.successCount()).isEqualTo(5);
            assertThat(summary.errorCount()).isEqualTo(0);
            assertThat(summary.skippedCount()).isEqualTo(0);
        }

        @Test void respectsDependencyOrder() {
            var graph = ModelGraph.fromModels(jaffleShop());
            var runner = new RecordingRunner();
            new GraphExecutor(graph, runner).execute();

            var order = runner.executionOrder;
            // Staging models must run before orders
            assertThat(order.indexOf("stg_orders")).isLessThan(order.indexOf("orders"));
            assertThat(order.indexOf("stg_customers")).isLessThan(order.indexOf("orders"));
            assertThat(order.indexOf("stg_payments")).isLessThan(order.indexOf("orders"));
            // orders must run before customer_orders
            assertThat(order.indexOf("orders")).isLessThan(order.indexOf("customer_orders"));
        }

        @Test void stagingModelsRunInParallel() {
            var graph = ModelGraph.fromModels(jaffleShop());
            var runner = new SlowRunner();
            new GraphExecutor(graph, runner).execute();

            // 3 staging models should run concurrently (level 0)
            assertThat(runner.peakConcurrency.get()).isGreaterThanOrEqualTo(2);
        }

        @Test void singleModel() {
            var graph = ModelGraph.fromModels(List.of(model("lonely")));
            var runner = new RecordingRunner();
            var summary = new GraphExecutor(graph, runner).execute();

            assertThat(summary.allSucceeded()).isTrue();
            assertThat(runner.executionOrder).containsExactly("lonely");
        }
    }

    @Nested class FailurePropagation {
        @Test void failedModelSkipsDownstream() {
            var graph = ModelGraph.fromModels(jaffleShop());
            // stg_orders fails → orders depends on it → should be skipped
            // → customer_orders depends on orders → should also be skipped
            var runner = new FailingRunner("stg_orders");
            var summary = new GraphExecutor(graph, runner).execute();

            assertThat(summary.results().get("stg_orders").status())
                    .isEqualTo(ModelResult.Status.ERROR);
            assertThat(summary.results().get("orders").status())
                    .isEqualTo(ModelResult.Status.SKIPPED);
            assertThat(summary.results().get("customer_orders").status())
                    .isEqualTo(ModelResult.Status.SKIPPED);
            // Other staging models still succeed
            assertThat(summary.results().get("stg_customers").status())
                    .isEqualTo(ModelResult.Status.SUCCESS);
            assertThat(summary.results().get("stg_payments").status())
                    .isEqualTo(ModelResult.Status.SUCCESS);
        }

        @Test void failureDoesNotAffectUnrelatedBranches() {
            // a ──→ b ──→ c
            // d ──→ e        (independent branch)
            var models = List.of(
                    model("a"), model("b", "a"), model("c", "b"),
                    model("d"), model("e", "d")
            );
            var graph = ModelGraph.fromModels(models);
            var runner = new FailingRunner("a");
            var summary = new GraphExecutor(graph, runner).execute();

            assertThat(summary.results().get("a").status()).isEqualTo(ModelResult.Status.ERROR);
            assertThat(summary.results().get("b").status()).isEqualTo(ModelResult.Status.SKIPPED);
            assertThat(summary.results().get("c").status()).isEqualTo(ModelResult.Status.SKIPPED);
            // Independent branch unaffected
            assertThat(summary.results().get("d").status()).isEqualTo(ModelResult.Status.SUCCESS);
            assertThat(summary.results().get("e").status()).isEqualTo(ModelResult.Status.SUCCESS);
        }

        @Test void summaryReportsFailures() {
            var graph = ModelGraph.fromModels(jaffleShop());
            var runner = new FailingRunner("stg_orders");
            var summary = new GraphExecutor(graph, runner).execute();

            assertThat(summary.allSucceeded()).isFalse();
            assertThat(summary.errorCount()).isEqualTo(1);
            assertThat(summary.skippedCount()).isEqualTo(2);
            assertThat(summary.successCount()).isEqualTo(2);
            assertThat(summary.failures()).hasSize(1);
            assertThat(summary.failures().getFirst().modelName()).isEqualTo("stg_orders");
        }
    }

    @Nested class ConcurrencyControl {
        @Test void respectsMaxConcurrency() {
            // 5 independent models, limit concurrency to 2
            var models = new ArrayList<ParsedModel>();
            for (int i = 0; i < 5; i++) models.add(model("leaf_" + i));

            var graph = ModelGraph.fromModels(models);
            var runner = new SlowRunner();
            new GraphExecutor(graph, runner, 2, null).execute();

            assertThat(runner.peakConcurrency.get()).isLessThanOrEqualTo(2);
        }

        @Test void unlimitedConcurrencyUsesAllThreads() {
            var models = new ArrayList<ParsedModel>();
            for (int i = 0; i < 5; i++) models.add(model("leaf_" + i));

            var graph = ModelGraph.fromModels(models);
            var runner = new SlowRunner();
            new GraphExecutor(graph, runner, 0, null).execute();

            // All 5 should run concurrently
            assertThat(runner.peakConcurrency.get()).isGreaterThanOrEqualTo(3);
        }
    }

    @Nested class SelectedExecution {
        @Test void executesOnlySelectedModels() {
            var graph = ModelGraph.fromModels(jaffleShop());
            var runner = new RecordingRunner();

            // Only run stg_orders and stg_customers
            var summary = new GraphExecutor(graph, runner)
                    .execute(Set.of("stg_orders", "stg_customers"));

            assertThat(summary.results()).hasSize(2);
            assertThat(runner.executionOrder)
                    .containsExactlyInAnyOrder("stg_orders", "stg_customers");
        }

        @Test void selectedWithUpstream() {
            var graph = ModelGraph.fromModels(jaffleShop());
            var runner = new RecordingRunner();

            // Simulate --select +orders (orders and all upstream)
            var selected = graph.select(List.of("orders"), true, false);
            var summary = new GraphExecutor(graph, runner).execute(selected);

            assertThat(summary.results()).hasSize(4); // 3 staging + orders
            assertThat(runner.executionOrder).doesNotContain("customer_orders");
        }
    }

    @Nested class Listener {
        @Test void firesEventsInOrder() {
            var events = Collections.synchronizedList(new ArrayList<String>());
            var listener = new ExecutionListener() {
                @Override public void onRunStart(int n) { events.add("start:" + n); }
                @Override public void onLevelStart(int i, int n) { events.add("level:" + i); }
                @Override public void onModelStart(String name) { events.add("model:" + name); }
                @Override public void onModelComplete(ModelResult r) { events.add("done:" + r.modelName()); }
                @Override public void onRunComplete(ExecutionSummary s) { events.add("end"); }
            };

            var graph = ModelGraph.fromModels(List.of(model("a"), model("b", "a")));
            new GraphExecutor(graph, new RecordingRunner(), 0, listener).execute();

            assertThat(events.getFirst()).isEqualTo("start:2");
            assertThat(events.getLast()).isEqualTo("end");
            assertThat(events).contains("level:0", "level:1", "model:a", "model:b");
        }
    }

    @Nested class LinearChain {
        @Test void executesInExactOrder() {
            var models = List.of(
                    model("a"), model("b", "a"), model("c", "b"),
                    model("d", "c"), model("e", "d")
            );
            var graph = ModelGraph.fromModels(models);
            var runner = new RecordingRunner();
            new GraphExecutor(graph, runner).execute();

            assertThat(runner.executionOrder).containsExactly("a", "b", "c", "d", "e");
        }
    }
}
