package com.dbtespresso.parser;

import com.dbtespresso.jinja.Dependency;
import java.nio.file.Path;
import java.util.*;

/**
 * A dbt resource discovered by scanning the project filesystem.
 *
 * @param name           derived from filename (stg_orders.sql → stg_orders)
 * @param resourceType   MODEL, TEST, SNAPSHOT, SEED, ANALYSIS
 * @param filePath       absolute path to the .sql file
 * @param rawSql         unrendered Jinja+SQL content
 * @param dependencies   refs/sources extracted via static analysis
 * @param config         inline config() key-value pairs
 */
public record ParsedModel(
        String name,
        ResourceType resourceType,
        Path filePath,
        String rawSql,
        List<Dependency> dependencies,
        Map<String, String> config
) {
    public ParsedModel {
        Objects.requireNonNull(name);
        Objects.requireNonNull(resourceType);
        Objects.requireNonNull(filePath);
        Objects.requireNonNull(rawSql);
        dependencies = dependencies != null ? List.copyOf(dependencies) : List.of();
        config = config != null ? Map.copyOf(config) : Map.of();
    }

    public enum ResourceType { MODEL, TEST, SNAPSHOT, SEED, ANALYSIS }

    public List<Dependency.ModelRef> modelRefs() {
        return dependencies.stream()
                .filter(Dependency.ModelRef.class::isInstance)
                .map(Dependency.ModelRef.class::cast).toList();
    }

    public List<Dependency.SourceRef> sourceRefs() {
        return dependencies.stream()
                .filter(Dependency.SourceRef.class::isInstance)
                .map(Dependency.SourceRef.class::cast).toList();
    }

    public String materialized() {
        return config.getOrDefault("materialized", "view");
    }
}
