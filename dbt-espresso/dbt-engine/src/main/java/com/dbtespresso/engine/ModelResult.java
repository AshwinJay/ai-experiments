package com.dbtespresso.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The result of executing a single dbt model against the warehouse.
 */
public record ModelResult(
        String modelName,
        Status status,
        Instant startedAt,
        Duration duration,
        long rowsAffected,
        String error
) {
    public enum Status { SUCCESS, ERROR, SKIPPED }

    public static ModelResult success(String name, Instant start, Duration dur, long rows) {
        return new ModelResult(name, Status.SUCCESS, start, dur, rows, null);
    }

    public static ModelResult error(String name, Instant start, Duration dur, String error) {
        return new ModelResult(name, Status.ERROR, start, dur, 0, error);
    }

    public static ModelResult skipped(String name) {
        return new ModelResult(name, Status.SKIPPED, Instant.now(), Duration.ZERO, 0,
                "Skipped due to upstream failure");
    }

    public boolean isSuccess() { return status == Status.SUCCESS; }
    public Optional<String> errorMessage() { return Optional.ofNullable(error); }
}
