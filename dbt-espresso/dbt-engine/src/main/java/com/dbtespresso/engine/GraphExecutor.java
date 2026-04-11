package com.dbtespresso.engine;

import com.dbtespresso.graph.ModelGraph;
import com.dbtespresso.parser.ParsedModel;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Executes dbt models in dependency order using Java 21 virtual threads.
 *
 * <h2>Execution strategy</h2>
 * <ol>
 *   <li>Compute execution levels from the DAG</li>
 *   <li>For each level, launch all models in parallel on virtual threads</li>
 *   <li>Wait for the entire level to complete before starting the next</li>
 *   <li>If a model fails, skip all its downstream dependents</li>
 * </ol>
 *
 * <h2>Why virtual threads?</h2>
 * Each model execution is I/O-bound (waiting on the warehouse). Virtual threads
 * let us run hundreds of models concurrently without the overhead of platform threads.
 * This is the Java equivalent of Rust's Tokio async runtime that dbt Fusion uses.
 *
 * <h2>Concurrency control</h2>
 * An optional Semaphore limits the number of concurrent warehouse connections.
 * Without it, all models in a level execute simultaneously.
 */
public final class GraphExecutor {

    private final ModelGraph graph;
    private final ModelRunner runner;
    private final int maxConcurrency;
    private final ExecutionListener listener;

    /**
     * @param graph          the model DAG
     * @param runner         strategy for executing a single model
     * @param maxConcurrency max parallel model executions (0 = unlimited)
     * @param listener       callback for execution events (nullable)
     */
    public GraphExecutor(ModelGraph graph, ModelRunner runner,
                         int maxConcurrency, ExecutionListener listener) {
        this.graph = graph;
        this.runner = runner;
        this.maxConcurrency = maxConcurrency;
        this.listener = listener != null ? listener : ExecutionListener.NOOP;
    }

    public GraphExecutor(ModelGraph graph, ModelRunner runner) {
        this(graph, runner, 0, null);
    }

    /**
     * Execute all models in the graph.
     * @return results for every model, in execution order
     */
    public ExecutionSummary execute() {
        List<List<String>> levels = graph.executionLevels();
        Map<String, ModelResult> results = new ConcurrentHashMap<>();
        Set<String> failed = ConcurrentHashMap.newKeySet();
        Instant runStart = Instant.now();

        listener.onRunStart(graph.size());

        Semaphore semaphore = maxConcurrency > 0
                ? new Semaphore(maxConcurrency) : null;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int levelIdx = 0; levelIdx < levels.size(); levelIdx++) {
                List<String> level = levels.get(levelIdx);
                listener.onLevelStart(levelIdx, level.size());

                List<Future<ModelResult>> futures = new ArrayList<>();

                for (String modelName : level) {
                    // Check if any upstream dependency failed
                    boolean upstreamFailed = graph.dependenciesOf(modelName).stream()
                            .anyMatch(failed::contains);

                    if (upstreamFailed) {
                        var skipped = ModelResult.skipped(modelName);
                        results.put(modelName, skipped);
                        failed.add(modelName);
                        listener.onModelComplete(skipped);
                        continue;
                    }

                    futures.add(executor.submit(() -> {
                        if (semaphore != null) semaphore.acquire();
                        try {
                            listener.onModelStart(modelName);
                            ParsedModel model = graph.model(modelName).orElseThrow();
                            ModelResult result = runner.run(model);
                            results.put(modelName, result);
                            if (!result.isSuccess()) failed.add(modelName);
                            listener.onModelComplete(result);
                            return result;
                        } finally {
                            if (semaphore != null) semaphore.release();
                        }
                    }));
                }

                // Wait for all models in this level to complete
                for (var future : futures) {
                    try {
                        future.get();
                    } catch (ExecutionException e) {
                        // Runner threw an unexpected exception
                        // The model is already tracked as failed via the runner contract
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Execution interrupted", e);
                    }
                }
            }
        }

        Duration totalDuration = Duration.between(runStart, Instant.now());
        var summary = new ExecutionSummary(results, totalDuration);
        listener.onRunComplete(summary);
        return summary;
    }

    /**
     * Execute only selected models (respecting dependency order).
     */
    public ExecutionSummary execute(Set<String> selectedModels) {
        // Build a subgraph containing only selected models
        // For now, filter levels to only include selected names
        List<List<String>> levels = graph.executionLevels();
        Map<String, ModelResult> results = new ConcurrentHashMap<>();
        Set<String> failed = ConcurrentHashMap.newKeySet();
        Instant runStart = Instant.now();

        listener.onRunStart(selectedModels.size());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (List<String> level : levels) {
                List<String> filtered = level.stream()
                        .filter(selectedModels::contains).toList();
                if (filtered.isEmpty()) continue;

                List<Future<ModelResult>> futures = new ArrayList<>();
                for (String name : filtered) {
                    boolean upstreamFailed = graph.dependenciesOf(name).stream()
                            .anyMatch(failed::contains);
                    if (upstreamFailed) {
                        results.put(name, ModelResult.skipped(name));
                        failed.add(name);
                        continue;
                    }
                    futures.add(executor.submit(() -> {
                        ParsedModel model = graph.model(name).orElseThrow();
                        ModelResult result = runner.run(model);
                        results.put(name, result);
                        if (!result.isSuccess()) failed.add(name);
                        return result;
                    }));
                }
                for (var f : futures) {
                    try { f.get(); }
                    catch (ExecutionException | InterruptedException e) {
                        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    }
                }
            }
        }

        return new ExecutionSummary(results, Duration.between(runStart, Instant.now()));
    }
}
