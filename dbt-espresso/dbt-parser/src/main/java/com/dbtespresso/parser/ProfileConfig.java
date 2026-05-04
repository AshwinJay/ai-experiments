package com.dbtespresso.parser;

import java.util.*;

/**
 * Parsed representation of one profile block in profiles.yml.
 *
 * <p>profiles.yml top level is a map of profile names; each profile has a
 * {@code target} (the default output) and an {@code outputs} map of named
 * connection configs.
 *
 * @param profileName   the top-level key in profiles.yml
 * @param defaultTarget the target name used when {@code --target} is not specified
 * @param outputs       named connection configs keyed by target name
 */
public record ProfileConfig(
        String profileName,
        String defaultTarget,
        Map<String, OutputConfig> outputs
) {
    public ProfileConfig {
        Objects.requireNonNull(profileName);
        Objects.requireNonNull(defaultTarget);
        outputs = outputs != null ? Map.copyOf(outputs) : Map.of();
    }

    /** Returns the active connection config for the default target. */
    public OutputConfig activeOutput() {
        OutputConfig out = outputs.get(defaultTarget);
        if (out == null) throw new IllegalStateException(
                "No output named '" + defaultTarget + "' in profile '" + profileName + "'");
        return out;
    }

    /**
     * Connection config for one named output target.
     *
     * @param type     warehouse type: "postgres", "snowflake", "bigquery", etc.
     * @param host     hostname (null for cloud warehouses like BigQuery/Snowflake)
     * @param port     TCP port (null if not applicable)
     * @param user     authentication username
     * @param password authentication password (may be null if using key-pair or OAuth)
     * @param dbname   database / catalog name
     * @param schema   default schema
     * @param threads  max parallel connections
     */
    public record OutputConfig(
            String type,
            String host,
            Integer port,
            String user,
            String password,
            String dbname,
            String schema,
            int threads
    ) {
        public OutputConfig {
            Objects.requireNonNull(type, "Output type is required");
            threads = threads > 0 ? threads : 1;
        }
    }
}
