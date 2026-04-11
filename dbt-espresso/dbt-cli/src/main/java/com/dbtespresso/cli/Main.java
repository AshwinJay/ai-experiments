package com.dbtespresso.cli;

import com.dbtespresso.engine.*;
import com.dbtespresso.graph.ModelGraph;
import com.dbtespresso.parser.*;

import java.nio.file.Path;
import java.time.*;

/**
 * Minimal CLI demonstrating the full dbt-espresso pipeline:
 * scan project → extract refs → build DAG → execute in parallel.
 *
 * In a real CLI you'd use Picocli for subcommands (parse, build, run, test, etc.)
 * and add flags for --select, --exclude, --threads, --target, etc.
 *
 * Usage: java -jar dbt-espresso.jar [project-dir]
 */
public class Main {

    public static void main(String[] args) {
        Path projectDir = args.length > 0
                ? Path.of(args[0])
                : Path.of(".");

        System.out.println("☕ dbt-espresso v0.1.0");
        System.out.println("   Scanning project: " + projectDir.toAbsolutePath());

        // Phase 1: Scan and parse
        var scanner = new DbtProjectScanner(projectDir);
        var models = scanner.scanModels();
        System.out.printf("   Found %d models%n", models.size());

        // Phase 2: Build DAG
        var graph = ModelGraph.fromModels(models);
        var levels = graph.executionLevels();
        System.out.printf("   DAG has %d levels, %d source dependencies%n",
                levels.size(), graph.sourceNodes().size());

        for (int i = 0; i < levels.size(); i++) {
            System.out.printf("   Level %d: %s%n", i, levels.get(i));
        }

        // Phase 3: Execute (dry run — just logs SQL instead of executing)
        var executor = new GraphExecutor(graph, dryRunRunner(), 0, consoleListener());
        var summary = executor.execute();

        System.out.printf("%n☕ Done! %s%n", summary);
    }

    /** Dry-run runner that simulates execution without a warehouse. */
    static ModelRunner dryRunRunner() {
        return model -> {
            // In a real implementation, this would:
            // 1. Render Jinja (replace ref() with actual table names)
            // 2. Wrap in CREATE TABLE AS / CREATE VIEW AS based on materialization
            // 3. Submit to the warehouse via ADBC/JDBC
            return ModelResult.success(model.name(), Instant.now(), Duration.ofMillis(5), 0);
        };
    }

    static ExecutionListener consoleListener() {
        return new ExecutionListener() {
            @Override public void onRunStart(int n) {
                System.out.printf("%n   Running %d models...%n", n);
            }
            @Override public void onLevelStart(int i, int n) {}
            @Override public void onModelStart(String name) {
                System.out.printf("   ▸ %s ...%n", name);
            }
            @Override public void onModelComplete(ModelResult r) {
                String icon = switch (r.status()) {
                    case SUCCESS -> "✓";
                    case ERROR -> "✗";
                    case SKIPPED -> "⊘";
                };
                System.out.printf("   %s %s [%s]%n", icon, r.modelName(), r.duration());
            }
            @Override public void onRunComplete(ExecutionSummary s) {}
        };
    }
}
