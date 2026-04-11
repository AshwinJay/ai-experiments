package com.dbtespresso.engine;

import java.time.Duration;
import java.util.*;

/**
 * Summary of a full DAG execution run.
 */
public record ExecutionSummary(
        Map<String, ModelResult> results,
        Duration totalDuration
) {
    public ExecutionSummary {
        results = Map.copyOf(results);
    }

    public long successCount() {
        return results.values().stream().filter(ModelResult::isSuccess).count();
    }

    public long errorCount() {
        return results.values().stream()
                .filter(r -> r.status() == ModelResult.Status.ERROR).count();
    }

    public long skippedCount() {
        return results.values().stream()
                .filter(r -> r.status() == ModelResult.Status.SKIPPED).count();
    }

    public List<ModelResult> failures() {
        return results.values().stream()
                .filter(r -> r.status() == ModelResult.Status.ERROR).toList();
    }

    public boolean allSucceeded() {
        return errorCount() == 0 && skippedCount() == 0;
    }

    @Override
    public String toString() {
        return "ExecutionSummary[total=%d, success=%d, error=%d, skipped=%d, duration=%s]"
                .formatted(results.size(), successCount(), errorCount(),
                        skippedCount(), totalDuration);
    }
}
